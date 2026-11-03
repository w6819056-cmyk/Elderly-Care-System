package com.smartelder.installer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.core.installation.InstallLicensePolicy;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.packaging.*;
import com.smartelder.module.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstallationEngineTest {
 @TempDir Path directory;JdbcClient jdbc;DriverManagerDataSource ds;ModuleRuntimeFiles files;InstallationEngine engine;TestHost host;String id,sha;InstallLicensePolicy policy;
 @BeforeEach void prepare()throws Exception{
  ds=new DriverManagerDataSource("jdbc:h2:mem:engine_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");Flyway.configure().dataSource(ds).locations("classpath:db/migration/tenant").load().migrate();jdbc=JdbcClient.create(ds);
  Path source=directory.resolve("Entry.java"),classes=directory.resolve("classes");Files.createDirectories(classes);Files.writeString(source,"""
   package com.smartelder.modules.probe;
   public class Entry implements com.smartelder.module.api.ModuleEntrypoint {
    public java.util.List<Class<?>> configurationClasses(){return java.util.List.of(Config.class);}
    public java.util.Map<String,String> permissionNames(){return java.util.Map.of();}
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) public static class Config {
     @org.springframework.context.annotation.Bean public String probeGreeting(){return "external-ready";}
    }
   }
   """);assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),source.toString())).isZero();
  var out=new ByteArrayOutputStream();try(var jar=new JarOutputStream(out);var paths=Files.walk(classes)){for(Path p:paths.filter(Files::isRegularFile).toList()){jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));jar.write(Files.readAllBytes(p));jar.closeEntry();}}
  byte[] jarContent=out.toByteArray(),sql="CREATE TABLE probe_record(id BIGINT PRIMARY KEY, name VARCHAR(30));".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var m=new ModulePackageManifest(1,"publisher",new ModuleDescriptor("probe","运行验证","1.0.0",99,List.of(),Set.of()),"2.0.0","3.0.0","com.smartelder.modules.probe.Entry",Map.of(),List.of("migrations/V1__probe.sql"),Map.of("backend/module.jar",ModulePackageVerifier.sha256(jarContent),"migrations/V1__probe.sql",ModulePackageVerifier.sha256(sql)));
  var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Path trust=directory.resolve("trust"),cache=directory.resolve("cache");Files.createDirectories(trust);Files.createDirectories(cache);Files.writeString(trust.resolve("publisher.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
  var mapper=new ObjectMapper();byte[] metadata=mapper.writeValueAsBytes(m);var signer=Signature.getInstance("Ed25519");signer.initSign(keys.getPrivate());signer.update(metadata);var archive=new ByteArrayOutputStream();
  try(var zip=new ZipOutputStream(archive)){for(var e:Map.of("manifest.json",metadata,"manifest.sig",Base64.getEncoder().encode(signer.sign()),"backend/module.jar",jarContent,"migrations/V1__probe.sql",sql).entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
  byte[] bytes=archive.toByteArray();sha=ModulePackageVerifier.sha256(bytes);Files.write(cache.resolve(sha+".caremod"),bytes);files=new ModuleRuntimeFiles(directory.resolve("runtime"),cache,trust);id=UUID.randomUUID().toString();
  jdbc.sql("INSERT INTO core_module_install_plan(public_id,module_code,package_version,package_sha256,manifest_json,status,created_by,confirmed_by,created_at,updated_at) VALUES(:id,'probe','1.0.0',:sha,:json,'READY_FOR_INSTALLER',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("id",id).param("sha",sha).param("json",mapper.writeValueAsString(m)).update();
  policy=mock(InstallLicensePolicy.class);host=new TestHost();engine=new InstallationEngine(jdbc,new DataSourceTransactionManager(ds),policy,files,host,Clock.systemUTC(),"2.0.0");
 }
 String status(){return jdbc.sql("SELECT status FROM core_module_install_plan WHERE public_id=:id").param("id",id).query(String.class).single();}
 @Test void migratesLoadsRealPluginAndOnlyThenMarksActive()throws Exception{engine.execute(id);assertThat(status()).isEqualTo("ACTIVE");assertThat(host.starts).isEqualTo(1);assertThat(host.loaded).isTrue();assertThat(jdbc.sql("SELECT COUNT(*) FROM probe_record").query(Long.class).single()).isZero();assertThat(files.index().modules().get("probe").sha256()).isEqualTo(sha);assertThatThrownBy(()->engine.execute(id)).hasMessageContaining("confirmed");}
 @Test void backupFailureRestartsOldServiceWithoutMigrating(){host.backupFails=true;assertThatThrownBy(()->engine.execute(id)).hasMessageContaining("BACKING_UP");assertThat(status()).isEqualTo("FAILED");assertThat(host.starts).isEqualTo(1);assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='probe_record'").query(Long.class).single()).isZero();}
 @Test void migrationFailureDoesNotRestartOldProgram(){host.migrationFails=true;assertThatThrownBy(()->engine.execute(id)).hasMessageContaining("MIGRATING");assertThat(status()).isEqualTo("RECOVERY_REQUIRED");assertThat(host.starts).isZero();}
 @Test void wrongHealthReceiptRequiresRecoveryRatherThanClaimingSuccess(){host.readyFails=true;assertThatThrownBy(()->engine.execute(id)).hasMessageContaining("HEALTH_CHECK");assertThat(status()).isEqualTo("RECOVERY_REQUIRED");assertThat(host.starts).isEqualTo(1);}
 @Test void changedPackageAndRevokedLicenseStopBeforeStoppingService()throws Exception{doThrow(new IllegalStateException("revoked")).when(policy).check(any());assertThatThrownBy(()->engine.execute(id)).hasMessageContaining("revoked");assertThat(host.stops).isZero();reset(policy);Files.write(directory.resolve("cache/"+sha+".caremod"),new byte[]{0});assertThatThrownBy(()->engine.execute(id)).hasMessageContaining("digest");assertThat(host.stops).isZero();}
 @Test void anotherFilesystemLockPreventsExecution()throws Exception{Files.createDirectories(files.root());try(var ch=java.nio.channels.FileChannel.open(files.root().resolve("install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=ch.lock()){assertThatThrownBy(()->engine.execute(id)).isInstanceOf(java.nio.channels.OverlappingFileLockException.class);assertThat(host.stops).isZero();}}
 @Test void sqlExecutionUsesVerifiedBytesEvenIfExtractedSqlWasReplaced()throws Exception{
  var verified=files.verify(sha);Path extracted=files.extract(verified);Files.writeString(extracted.resolve("migrations/V1__probe.sql"),"CREATE TABLE evil_record(id BIGINT);");
  VerifiedMigrationRunner.migrate(ds,verified);assertThat(jdbc.sql("SELECT COUNT(*) FROM probe_record").query(Long.class).single()).isZero();assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='evil_record'").query(Long.class).single()).isZero();
 }
 class TestHost implements InstallationHost {
  int starts,stops;boolean backupFails,migrationFails,readyFails,loaded;
  public void stop(){stops++;}public Backup backup(String plan){if(backupFails)throw new IllegalStateException("backup failure");return new Backup(directory.resolve("backup.sql").toString(),"0".repeat(64));}
  public void migrate(VerifiedModulePackage m,Path payload){VerifiedMigrationRunner.migrate(ds,m);if(migrationFails)throw new IllegalStateException("migration failure");}
  public void start(){starts++;}
  public void awaitReady(ModuleRuntimeFiles.Index expected)throws Exception{if(readyFails)throw new IllegalStateException("receipt mismatch");if(expected.modules().isEmpty())return;try(var loader=new ModuleRuntimeLoader();var context=new AnnotationConfigApplicationContext()){for(var module:loader.load(files,"2.0.0",getClass().getClassLoader()))module.configurations().forEach(context::register);context.refresh();loaded=context.getBean("probeGreeting").equals("external-ready");assertThat(loaded).isTrue();}}
 }
}
