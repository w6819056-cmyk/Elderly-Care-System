package com.smartelder.api.tenant.installation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.api.tenant.licensing.PlatformLicenseClient;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.module.packaging.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class ModuleInstallService {
 private final JdbcClient jdbc;private final ObjectProvider<PlatformLicenseClient> clients;private final TenantLicenseProperties properties;
 private final InstallLicensePolicy policy;private final ObjectMapper mapper;private final Clock clock;private final TransactionTemplate tx;
 private final Path root,trust;private final String coreVersion;
 public ModuleInstallService(JdbcClient jdbc,ObjectProvider<PlatformLicenseClient> clients,TenantLicenseProperties properties,InstallLicensePolicy policy,ObjectMapper mapper,Clock clock,PlatformTransactionManager transactions,
  @Value("${app.module-install.directory:data/module-install}") String directory,@Value("${app.module-install.trust-directory:data/module-publisher-keys}") String trust,@Value("${app.module-install.core-version:2.1.0}") String coreVersion){
  this.jdbc=jdbc;this.clients=clients;this.properties=properties;this.policy=policy;this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(transactions);this.root=Path.of(directory).toAbsolutePath().normalize();this.trust=Path.of(trust).toAbsolutePath().normalize();this.coreVersion=coreVersion;
 }
 private void permission(TenantAccessContext a,boolean write){if(!"HQ".equals(a.staffType())||!a.permissionCodes().contains(write?"core.module.install":"core.module.view"))throw new TenantStaffAuthException(403,"PERMISSION_DENIED","仅有相应权限的企业总部员工可操作安装中心");}
 private PlatformLicenseClient client(){if(!properties.isSyncConfigured()||clients.getIfAvailable()==null)throw new IllegalStateException("企业实例尚未配置激活与平台连接，请先配置并同步授权");return clients.getIfAvailable();}
 @org.springframework.beans.factory.annotation.Value("${app.module-install.runtime-directory:data/module-runtime}")
 private String runtimeDirectory="data/module-runtime";
 public Map<String,Object> configuration(TenantAccessContext a){permission(a,false);boolean ready=executorReady();return Map.of("instanceConfigured",properties.isSyncConfigured()&&clients.getIfAvailable()!=null,"trustedPublisherCount",TrustedPublisherKeys.load(trust).size(),"coreVersion",coreVersion,"executorReady",ready,"executorMode",ready?"WATCH":"OFFLINE");}
 private boolean executorReady(){
  try{Path p=Path.of(runtimeDirectory).toAbsolutePath().resolve("installer-heartbeat.json");com.smartelder.module.runtime.ModuleRuntimeFiles.noSymlinks(p);if(!Files.exists(p)||Files.size(p)>4096)return false;var json=mapper.readTree(Files.readAllBytes(p));Instant checked=Instant.parse(json.path("checkedAt").asText());long pid=json.path("pid").asLong(-1);return json.path("ready").asBoolean(false)&&!checked.isAfter(clock.instant().plusSeconds(5))&&checked.isAfter(clock.instant().minusSeconds(30))&&pid>0&&ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(h->h.info().startInstant()).map(t->t.toString().equals(json.path("processStartedAt").asText())).orElse(false);}catch(Exception e){return false;}
 }
 public Object available(TenantAccessContext a){permission(a,false);var response=client().availablePackages();if(response==null||!"OK".equals(response.path("code").asText())||!response.path("data").isArray())throw new IllegalStateException("平台模块列表返回无效");return response.get("data");}
 public List<Plan> plans(TenantAccessContext a){permission(a,false);return jdbc.sql("SELECT p.public_id,p.module_code,p.package_version,p.package_sha256,p.status,p.created_at,p.updated_at,e.phase,e.error_message,e.backup_path FROM core_module_install_plan p LEFT JOIN core_module_install_execution e ON e.plan_id=p.public_id ORDER BY p.created_at DESC").query((r,n)->new Plan(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant(),r.getTimestamp(7).toInstant(),r.getString(8),r.getString(9),r.getString(10)!=null)).list();}
 public Plan prepare(String code,String version,TenantAccessContext a,String trace){
  permission(a,true);byte[] bytes=client().downloadPackage(code,version);var verified=verify(bytes);var m=verified.manifest();
  if(!m.module().code().equals(code)||!m.module().version().equals(version))throw new IllegalArgumentException("下载的模块身份与请求不一致");
  check(m);store(bytes,verified.packageSha256());
  return tx.execute(status->{mutex();check(m);
   var existing=plans(a).stream().filter(p->p.moduleCode().equals(code)&&p.version().equals(version)&&p.sha256().equals(verified.packageSha256())&&Set.of("AWAITING_CONFIRMATION","READY_FOR_INSTALLER").contains(p.status())&&clock.instant().isBefore(p.createdAt().plus(Duration.ofHours(24)))).findFirst();if(existing.isPresent())return existing.get();
   String id=UUID.randomUUID().toString();Timestamp now=Timestamp.from(clock.instant());
   jdbc.sql("INSERT INTO core_module_install_plan(public_id,module_code,package_version,package_sha256,manifest_json,status,created_by,created_at,updated_at) VALUES(:id,:code,:v,:sha,:manifest,'AWAITING_CONFIRMATION',:actor,:now,:now)").param("id",id).param("code",code).param("v",version).param("sha",verified.packageSha256()).param("manifest",json(m)).param("actor",a.staffId()).param("now",now).update();audit(id,"MODULE_INSTALL_PREPARED",a,trace);return plans(a).stream().filter(p->p.publicId().equals(id)).findFirst().orElseThrow();});
 }
 public void change(String id,String sha,boolean cancel,TenantAccessContext a,String trace){permission(a,true);tx.executeWithoutResult(transaction->{
  mutex();var row=jdbc.sql("SELECT package_sha256,status,created_at,module_code FROM core_module_install_plan WHERE public_id=:id FOR UPDATE").param("id",id).query((r,n)->new Current(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant(),r.getString(4))).optional().orElseThrow(()->new IllegalArgumentException("安装计划不存在"));
  if(!row.sha().equals(sha))throw new IllegalStateException("安装包摘要已改变，请重新读取计划");
  if(cancel){if(!Set.of("AWAITING_CONFIRMATION","READY_FOR_INSTALLER").contains(row.status()))throw new IllegalStateException("当前计划不能撤销");}
  else{
   if(!row.status().equals("AWAITING_CONFIRMATION"))throw new IllegalStateException("计划状态已改变，请刷新");
   if(!clock.instant().isBefore(row.created().plus(Duration.ofHours(24))))throw new IllegalStateException("计划已超过24小时，请重新下载生成计划");
   var verified=verify(read(row.sha()));if(!verified.packageSha256().equals(row.sha()))throw new IllegalStateException("安装包已改变");check(verified.manifest());
   if(jdbc.sql("SELECT COUNT(*) FROM core_module_install_plan WHERE module_code=:code AND status='READY_FOR_INSTALLER'").param("code",row.code()).query(Long.class).single()>0)throw new IllegalStateException("该模块已有等待执行的安装计划");
  }
  jdbc.sql("UPDATE core_module_install_plan SET status=:status,confirmed_by=:actor,updated_at=:now WHERE public_id=:id").param("status",cancel?"CANCELLED":"READY_FOR_INSTALLER").param("actor",a.staffId()).param("now",Timestamp.from(clock.instant())).param("id",id).update();audit(id,cancel?"MODULE_INSTALL_CANCELLED":"MODULE_INSTALL_CONFIRMED",a,trace);
 });}
 private void mutex(){jdbc.sql("SELECT id FROM core_module_install_mutex WHERE id=1 FOR UPDATE").query(Long.class).single();}
 private void check(ModulePackageManifest m){if(!m.supportsCore(coreVersion))throw new IllegalStateException("模块与当前底座版本不兼容");policy.check(m);}
 private VerifiedModulePackage verify(byte[] bytes){return new ModulePackageVerifier(TrustedPublisherKeys.load(trust)).verify(bytes);}
 private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("安装清单无效",e);}}
 private Path file(String sha){if(!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("摘要格式无效");return root.resolve(sha+".caremod");}
 private byte[] read(String sha){try{Path p=file(sha);if(Files.isSymbolicLink(p)||Files.size(p)>ModulePackageVerifier.MAX_ARCHIVE_BYTES)throw new IllegalStateException("缓存模块包无效");byte[] bytes=Files.readAllBytes(p);if(!ModulePackageVerifier.sha256(bytes).equals(sha))throw new IllegalStateException("缓存模块包摘要不符");return bytes;}catch(IOException e){throw new IllegalStateException("无法读取模块包缓存",e);}}
 private void store(byte[] bytes,String sha){try{Files.createDirectories(root);Path target=file(sha);if(Files.exists(target)){read(sha);return;}Path temp=Files.createTempFile(root,"download-",".tmp");try{Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(IOException e){throw new IllegalStateException("无法缓存模块包",e);}}
 private void audit(String id,String action,TenantAccessContext a,String trace){jdbc.sql("INSERT INTO audit_tenant_log(actor_staff_id,action,target_type,target_public_id,trace_id,details_json,created_at) VALUES(:actor,:action,'MODULE_INSTALL',:id,:trace,'{}',:now)").param("actor",a.staffId()).param("action",action).param("id",id).param("trace",trace).param("now",Timestamp.from(clock.instant())).update();}
 private record Current(String sha,String status,Instant created,String code){}
 public record Plan(String publicId,String moduleCode,String version,String sha256,String status,Instant createdAt,Instant updatedAt,String phase,String errorMessage,boolean backupAvailable){}
}
