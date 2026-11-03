package com.smartelder.installer;

import com.smartelder.core.installation.InstallLicensePolicy;
import com.smartelder.module.runtime.*;
import com.smartelder.module.packaging.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** One external executor owns the process lifecycle. No automatic recovery after irreversible work. */
public final class InstallationEngine {
 private final JdbcClient jdbc;private final TransactionTemplate tx;private final InstallLicensePolicy policy;
 private final ModuleRuntimeFiles files;private final InstallationHost host;private final Clock clock;private final String coreVersion;
 public InstallationEngine(JdbcClient jdbc,PlatformTransactionManager transactionManager,InstallLicensePolicy policy,ModuleRuntimeFiles files,InstallationHost host,Clock clock,String coreVersion){this.jdbc=jdbc;this.tx=new TransactionTemplate(transactionManager);this.policy=policy;this.files=files;this.host=host;this.clock=clock;this.coreVersion=coreVersion;}
 public void execute(String id)throws Exception{
  UUID.fromString(id);ModuleRuntimeFiles.noSymlinks(files.root());Files.createDirectories(files.root());
  try(var channel=FileChannel.open(files.root().resolve("install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
   if(lock==null)throw new IllegalStateException("Another installer owns the runtime lock");
   var plan=jdbc.sql("SELECT module_code,package_version,package_sha256,status,created_at,confirmed_by FROM core_module_install_plan WHERE public_id=:id").param("id",id).query((r,n)->new Plan(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant(),r.getObject(6)!=null)).optional().orElseThrow(()->new IllegalArgumentException("Plan not found"));
   if(!plan.status().equals("READY_FOR_INSTALLER")||!plan.confirmed())throw new IllegalStateException("Plan is not confirmed");
   if(!clock.instant().isBefore(plan.created().plus(Duration.ofHours(24))))throw new IllegalStateException("Plan expired; create a new plan");
   var verified=files.verify(plan.sha());var manifest=verified.manifest();
   if(!manifest.module().code().equals(plan.code())||!manifest.module().version().equals(plan.version())||!manifest.supportsCore(coreVersion))throw new IllegalArgumentException("Plan/package identity or core compatibility changed");
   policy.check(manifest);Path payload=files.extract(verified);
   ModuleRuntimeLoader.validateJar(verified.file("backend/module.jar"),plan.code(),manifest.entryClass(),getClass().getClassLoader());
   var previous=files.index();
   if(previous.modules().containsKey(plan.code())){
    String current=files.verify(previous.modules().get(plan.code()).sha256()).manifest().module().version();
    if(compareVersions(plan.version(),current)<=0)throw new IllegalStateException("Module downgrade or same-version replacement is not allowed");
   }
   var nextMap=new TreeMap<>(previous.modules());nextMap.put(plan.code(),new ModuleRuntimeFiles.Entry(id,plan.sha()));var next=new ModuleRuntimeFiles.Index(1,nextMap);
   // Validate the complete dependency graph before stopping any running service.
   var descriptors=new ArrayList<com.smartelder.module.api.ModuleDescriptor>();for(var entry:nextMap.values())descriptors.add(files.verify(entry.sha256()).manifest().module());new com.smartelder.module.api.ModuleRegistry(descriptors);
   host.preflight();claim(id);
   boolean stopAttempted=false,irreversible=false,indexChanged=false;
   try{
    phase(id,"STOPPING");stopAttempted=true;host.stop();phase(id,"BACKING_UP");var backup=host.backup(id);
    if(backup==null||backup.path()==null||backup.sha256()==null||!backup.sha256().matches("[a-f0-9]{64}"))throw new IllegalStateException("Backup did not produce a verified artifact");
    jdbc.sql("UPDATE core_module_install_execution SET backup_path=:path,backup_sha256=:sha WHERE plan_id=:id").param("path",backup.path()).param("sha",backup.sha256()).param("id",id).update();
    policy.check(manifest);files.verifyPayload(payload,verified);
    phase(id,"MIGRATING");irreversible=!manifest.migrations().isEmpty();host.migrate(verified,payload);
    phase(id,"SWITCHING");files.index(next);indexChanged=true;
    status(id,"RESTART_PENDING");phase(id,"STARTING");irreversible=true;host.start();phase(id,"HEALTH_CHECK");host.awaitReady(next);
    tx.executeWithoutResult(transaction->{mutex();jdbc.sql("UPDATE core_module_install_plan SET status='SUPERSEDED',updated_at=:now WHERE module_code=:code AND status='ACTIVE' AND public_id<>:id").param("code",plan.code()).param("id",id).param("now",now()).update();status(id,"ACTIVE");phase(id,"COMPLETE");});
   }catch(Exception|LinkageError failure){
    String message="Installation failed during "+currentPhase(id)+"; "+failure.getClass().getSimpleName();
    if(irreversible){try{host.stop();}catch(Exception ignored){}status(id,"RECOVERY_REQUIRED");}
    else{
     try{if(indexChanged)files.index(previous);if(stopAttempted){host.start();host.awaitReady(previous);}status(id,"FAILED");}
     catch(Exception recovery){status(id,"RECOVERY_REQUIRED");message+="; old service recovery failed";}
    }
    jdbc.sql("UPDATE core_module_install_execution SET error_message=:error,updated_at=:now WHERE plan_id=:id").param("error",message).param("now",now()).param("id",id).update();
    throw new IllegalStateException(message,failure);
   }
  }
 }
 private void claim(String id){tx.executeWithoutResult(t->{mutex();if(jdbc.sql("SELECT COUNT(*) FROM core_module_install_plan WHERE status IN ('INSTALLING','RESTART_PENDING','RECOVERY_REQUIRED')").query(Long.class).single()>0)throw new IllegalStateException("An installation is unfinished or needs recovery");int count=jdbc.sql("UPDATE core_module_install_plan SET status='INSTALLING',updated_at=:now WHERE public_id=:id AND status='READY_FOR_INSTALLER' AND confirmed_by IS NOT NULL").param("id",id).param("now",now()).update();if(count!=1)throw new IllegalStateException("Plan changed before claim");jdbc.sql("INSERT INTO core_module_install_execution(plan_id,phase,started_at,updated_at) VALUES(:id,'CLAIMED',:now,:now)").param("id",id).param("now",now()).update();});}
 private void mutex(){jdbc.sql("SELECT id FROM core_module_install_mutex WHERE id=1 FOR UPDATE").query(Long.class).single();}
 private Timestamp now(){return Timestamp.from(clock.instant());}
 private static int compareVersions(String a,String b){String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<3;i++){int c=new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));if(c!=0)return c;}return 0;}
 private void status(String id,String state){jdbc.sql("UPDATE core_module_install_plan SET status=:status,updated_at=:now WHERE public_id=:id").param("status",state).param("now",now()).param("id",id).update();}
 private void phase(String id,String phase){jdbc.sql("UPDATE core_module_install_execution SET phase=:phase,updated_at=:now WHERE plan_id=:id").param("phase",phase).param("now",now()).param("id",id).update();}
 private String currentPhase(String id){return jdbc.sql("SELECT phase FROM core_module_install_execution WHERE plan_id=:id").param("id",id).query(String.class).single();}
 private record Plan(String code,String version,String sha,String status,Instant created,boolean confirmed){}
}
