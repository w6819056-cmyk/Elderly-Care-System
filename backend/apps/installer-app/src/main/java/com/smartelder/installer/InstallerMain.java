package com.smartelder.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.core.installation.InstallLicensePolicy;
import com.smartelder.module.runtime.ModuleRuntimeFiles;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class InstallerMain {
 public static void main(String[] args)throws Exception {
  if(args.length!=2||(!args[1].equals("--watch")&&!args[1].startsWith("--plan=")))throw new IllegalArgumentException("Usage: java -jar installer-app.jar <operator-config.json> --watch|--plan=<uuid>");
  Path configFile=Path.of(args[0]).toAbsolutePath();ModuleRuntimeFiles.noSymlinks(configFile);if(Files.size(configFile)>64*1024)throw new IllegalArgumentException("Config too large");
  var config=new ObjectMapper().readValue(Files.readAllBytes(configFile),InstallerConfiguration.class);config.validateFiles();
  String password=System.getenv(config.passwordEnvironment());if(password==null||password.isBlank())throw new IllegalArgumentException("Database password environment is not set");
  var ds=new DriverManagerDataSource(config.jdbcUrl(),config.username(),password);var jdbc=JdbcClient.create(ds);jdbc.sql("SELECT 1 FROM core_module_install_mutex WHERE id=1").query(Integer.class).single();
  var files=new ModuleRuntimeFiles(Path.of(config.runtimeDirectory()),Path.of(config.cacheDirectory()),Path.of(config.trustDirectory()));var clock=Clock.systemUTC();
  var engine=new InstallationEngine(jdbc,new DataSourceTransactionManager(ds),new InstallLicensePolicy(jdbc,clock),files,new ProcessInstallationHost(config,ds,password,files),clock,config.coreVersion());
  if(args[1].startsWith("--plan=")){engine.execute(args[1].substring(7));return;}
  Files.createDirectories(files.root());Path heartbeat=files.root().resolve("installer-heartbeat.json");
  try(var channel=FileChannel.open(files.root().resolve("watch.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
   if(lock==null)throw new IllegalStateException("Another installer watcher is already running");
   try{while(!Thread.currentThread().isInterrupted()){
    boolean recovery=jdbc.sql("SELECT COUNT(*) FROM core_module_install_plan WHERE status IN ('INSTALLING','RESTART_PENDING','RECOVERY_REQUIRED')").query(Long.class).single()>0;
    ModuleRuntimeFiles.atomicJson(heartbeat,Map.of("pid",ProcessHandle.current().pid(),"processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString(),"checkedAt",clock.instant().toString(),"ready",!recovery));
    if(!recovery){var pending=jdbc.sql("SELECT public_id FROM core_module_install_plan WHERE status='READY_FOR_INSTALLER' ORDER BY created_at").query(String.class).list();if(!pending.isEmpty()){
     String id=pending.get(0);try{engine.execute(id);}catch(Exception|LinkageError e){
      // Preflight failures never stop the process. Mark only plans that were not claimed.
      jdbc.sql("UPDATE core_module_install_plan SET status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE public_id=:id AND status='READY_FOR_INSTALLER'").param("id",id).update();System.err.println("Module installation stopped; inspect execution record for plan "+id);
     }
    }}Thread.sleep(3000);
   }}finally{Files.deleteIfExists(heartbeat);}
  }
 }
}
