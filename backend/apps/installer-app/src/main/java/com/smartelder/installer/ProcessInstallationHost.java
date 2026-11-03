package com.smartelder.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.module.packaging.*;
import com.smartelder.module.runtime.*;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ProcessInstallationHost implements InstallationHost {
 private final InstallerConfiguration config;private final DataSource dataSource;private final String password;private final ModuleRuntimeFiles files;
 private ProcessIdentity stoppedIdentity;
 private record ProcessIdentity(long pid,java.time.Instant startedAt){}
 private boolean sameProcess(ProcessIdentity identity){return identity!=null&&ProcessHandle.of(identity.pid()).filter(ProcessHandle::isAlive).flatMap(h->h.info().startInstant()).map(identity.startedAt()::equals).orElse(false);}
 public ProcessInstallationHost(InstallerConfiguration config,DataSource dataSource,String password,ModuleRuntimeFiles files){this.config=config;this.dataSource=dataSource;this.password=password;this.files=files;}
 @Override public void preflight()throws Exception{
  Path receipt=files.root().resolve("runtime-ready.json");ModuleRuntimeFiles.noSymlinks(receipt);
  if(!Files.exists(receipt)||Files.size(receipt)>1024*1024)throw new IllegalStateException("Tenant must publish a runtime startup receipt before managed installation");
  var json=new ObjectMapper().readTree(Files.readAllBytes(receipt));long pid=json.path("pid").asLong(-1);if(pid<1)throw new IllegalStateException("Invalid tenant process identity");
  stoppedIdentity=new ProcessIdentity(pid,Instant.parse(json.path("processStartedAt").asText()));
  if(!sameProcess(stoppedIdentity))throw new IllegalStateException("Tenant startup receipt does not identify a live process");
 }
 @Override public void stop()throws Exception{
  if(stoppedIdentity==null)throw new IllegalStateException("No preflight-verified process identity");
  command(config.stopCommand(),"stop");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
  while(sameProcess(stoppedIdentity)){if(System.nanoTime()>end)throw new IllegalStateException("Tenant process did not stop");Thread.sleep(200);}
 }
 @Override public void start()throws Exception{if(sameProcess(stoppedIdentity))throw new IllegalStateException("Refusing to start another tenant while the preflight process is still running");Files.deleteIfExists(files.root().resolve("runtime-ready.json"));command(config.startCommand(),"start");}
 protected void command(List<String> args,String name)throws Exception{
  Path logs=files.root().resolve("process-logs");Files.createDirectories(logs);Path log=Files.createTempFile(logs,name+"-",".log");
  Process process=new ProcessBuilder(args).directory(Path.of(config.workingDirectory()).toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  try{if(!process.waitFor(config.timeoutSeconds(),TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Lifecycle command timed out");}}catch(InterruptedException e){process.destroyForcibly();throw e;}
  if(process.exitValue()!=0)throw new IllegalStateException("Lifecycle command failed; inspect protected process logs");
 }
 @Override public Backup backup(String planId)throws Exception{
  UUID.fromString(planId);URI db=URI.create(config.jdbcUrl().substring(5));String database=db.getPath().substring(1);
  if(!database.matches("[A-Za-z0-9_]+")||db.getHost()==null)throw new IllegalArgumentException("Database URL cannot be used for backup");
  Path backups=files.root().resolve("backups");Files.createDirectories(backups);Path output=backups.resolve(planId+".sql");
  if(Files.exists(output))throw new IllegalStateException("Backup already exists; do not overwrite a prior recovery point");
  List<String> args=List.of(config.dumpExecutable(),"--single-transaction","--quick","--hex-blob","--routines","--events","--triggers","--set-gtid-purged=OFF","--no-tablespaces","--default-character-set=utf8mb4","--host="+db.getHost(),"--port="+(db.getPort()<0?3306:db.getPort()),"--user="+config.username(),database);
  ProcessBuilder builder=new ProcessBuilder(args).redirectOutput(output.toFile()).redirectError(backups.resolve(planId+".stderr").toFile());builder.environment().put("MYSQL_PWD",password);
  Process process=builder.start();try{if(!process.waitFor(config.timeoutSeconds(),TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Database backup timed out");}}catch(InterruptedException e){process.destroyForcibly();throw e;}
  if(process.exitValue()!=0||Files.size(output)==0)throw new IllegalStateException("Database backup failed; migration was not started");
  try(var stream=Files.newInputStream(output)){var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);return new Backup(output.toString(),HexFormat.of().formatHex(digest.digest()));}
 }
 @Override public void migrate(VerifiedModulePackage module,Path payload){
  VerifiedMigrationRunner.migrate(dataSource,module);
 }
 @Override public void awaitReady(ModuleRuntimeFiles.Index expected)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(config.timeoutSeconds());var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  while(System.nanoTime()<deadline){
   try{
    var response=client.send(HttpRequest.newBuilder(URI.create(config.healthUrl())).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
    com.fasterxml.jackson.databind.JsonNode healthJson;try(var body=response.body()){byte[] bytes=body.readNBytes(8193);if(bytes.length>8192)continue;healthJson=new ObjectMapper().readTree(bytes);}
    if(response.statusCode()==200){
     Path receipt=files.root().resolve("runtime-ready.json");ModuleRuntimeFiles.noSymlinks(receipt);
     if(Files.exists(receipt)&&Files.size(receipt)<1024*1024){var json=new ObjectMapper().readTree(Files.readAllBytes(receipt));boolean matched=true;
      for(var entry:expected.modules().entrySet()){var value=json.path("modules").path(entry.getKey());matched&=entry.getValue().planId().equals(value.path("planId").asText())&&entry.getValue().sha256().equals(value.path("sha256").asText());}
      URI health=URI.create(config.healthUrl());int port=health.getPort()<0?(health.getScheme().equals("https")?443:80):health.getPort();
      long pid=json.path("pid").asLong(-1);if(pid<1)continue;
      var identity=new ProcessIdentity(pid,Instant.parse(json.path("processStartedAt").asText()));
      boolean bound=json.path("startupId").asText().length()>10&&json.path("startupId").asText().equals(healthJson.path("startupId").asText())&&pid==healthJson.path("pid").asLong(-1)&&identity.startedAt().toString().equals(healthJson.path("processStartedAt").asText());
      if(matched&&bound&&json.path("port").asInt(-1)==port&&sameProcess(identity)){stoppedIdentity=identity;return;}
     }
    }
   }catch(Exception ignored){}
   Thread.sleep(500);
  }
  throw new IllegalStateException("New process health or signed-package startup receipt did not match");
 }
}
