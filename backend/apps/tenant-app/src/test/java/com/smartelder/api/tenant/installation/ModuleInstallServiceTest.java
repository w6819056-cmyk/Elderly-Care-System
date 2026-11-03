package com.smartelder.api.tenant.installation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.licensing.PlatformLicenseClient;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.core.api.access.*;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.packaging.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ModuleInstallServiceTest {
 @TempDir Path directory;
 ModuleInstallService service;JdbcClient jdbc;InstallLicensePolicy policy;PlatformLicenseClient client;
 Clock clock=Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"),ZoneOffset.UTC);
 TenantAccessContext actor=new TenantAccessContext(1,"staff-test","HQ",null,Set.of(),Set.of("core.module.view","core.module.install"),new DataScope("ALL",Set.of()));
 @BeforeEach void setup()throws Exception{
  var ds=new DriverManagerDataSource("jdbc:h2:mem:install_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  Flyway.configure().dataSource(ds).locations("classpath:db/migration/tenant").load().migrate();jdbc=JdbcClient.create(ds);
  policy=mock(InstallLicensePolicy.class);client=mock(PlatformLicenseClient.class);var factory=new StaticListableBeanFactory();factory.addBean("client",client);
  var properties=new TenantLicenseProperties();properties.setPlatformBaseUrl("http://localhost");properties.setInstancePublicId("instance");properties.setInstancePrivateKeyPem("configured");properties.setOrgNo("org");properties.setInstanceFingerprint("fingerprint");
  service=new ModuleInstallService(jdbc,factory.getBeanProvider(PlatformLicenseClient.class),properties,policy,new ObjectMapper(),clock,new DataSourceTransactionManager(ds),directory.resolve("cache").toString(),directory.resolve("trust").toString(),"2.0.0");
  var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Files.createDirectories(directory.resolve("trust"));Files.writeString(directory.resolve("trust/test.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
  byte[] jar={1,2,3};var m=new ModulePackageManifest(1,"test",new ModuleDescriptor("probe","校验模块","1.0.0",10,List.of(),Set.of()),"2.0.0","3.0.0","com.example.Probe",Map.of(),List.of(),Map.of("backend/module.jar",ModulePackageVerifier.sha256(jar)));
  byte[] metadata=new ObjectMapper().writeValueAsBytes(m);var signer=Signature.getInstance("Ed25519");signer.initSign(keys.getPrivate());signer.update(metadata);var output=new ByteArrayOutputStream();
  try(var zip=new ZipOutputStream(output)){for(var item:Map.of("manifest.json",metadata,"manifest.sig",Base64.getEncoder().encode(signer.sign()),"backend/module.jar",jar).entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}
  when(client.downloadPackage("probe","1.0.0")).thenReturn(output.toByteArray());
 }
 @Test void createsIdempotentPlanThenConfirmsAndCancelsWithoutInstalling(){
  var plan=service.prepare("probe","1.0.0",actor,"trace");assertThat(plan.status()).isEqualTo("AWAITING_CONFIRMATION");assertThat(service.prepare("probe","1.0.0",actor,"trace").publicId()).isEqualTo(plan.publicId());
  service.change(plan.publicId(),plan.sha256(),false,actor,"trace");assertThat(service.plans(actor).get(0).status()).isEqualTo("READY_FOR_INSTALLER");assertThat(service.configuration(actor).get("executorReady")).isEqualTo(false);
  assertThatThrownBy(()->service.change(plan.publicId(),plan.sha256(),false,actor,"trace")).isInstanceOf(IllegalStateException.class);
  service.change(plan.publicId(),plan.sha256(),true,actor,"trace");assertThat(service.plans(actor).get(0).status()).isEqualTo("CANCELLED");
 }
 @Test void rejectsChangedDigestAndRevokedLicenseBeforeConfirmation(){
  var plan=service.prepare("probe","1.0.0",actor,"trace");assertThatThrownBy(()->service.change(plan.publicId(),"0".repeat(64),false,actor,"trace")).isInstanceOf(IllegalStateException.class);
  doThrow(new IllegalStateException("授权已撤销")).when(policy).check(any());assertThatThrownBy(()->service.change(plan.publicId(),plan.sha256(),false,actor,"trace")).hasMessageContaining("授权已撤销");assertThat(service.plans(actor).get(0).status()).isEqualTo("AWAITING_CONFIRMATION");
 }
 @Test void detectsCacheTampering(){var plan=service.prepare("probe","1.0.0",actor,"trace");try{Files.write(directory.resolve("cache/"+plan.sha256()+".caremod"),new byte[]{0});}catch(IOException e){throw new RuntimeException(e);}assertThatThrownBy(()->service.change(plan.publicId(),plan.sha256(),false,actor,"trace")).hasMessageContaining("摘要");}
 @Test void rejectsExpiredPlans(){var plan=service.prepare("probe","1.0.0",actor,"trace");jdbc.sql("UPDATE core_module_install_plan SET created_at=:date").param("date",java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(25)))).update();assertThatThrownBy(()->service.change(plan.publicId(),plan.sha256(),false,actor,"trace")).hasMessageContaining("24小时");}
 @Test void deniesStoreAndReadOnlyStaff(){var store=new TenantAccessContext(2,"store-test","STORE",1L,Set.of(),actor.permissionCodes(),new DataScope("STORES",Set.of(1L)));assertThatThrownBy(()->service.prepare("probe","1.0.0",store,"trace")).hasMessageContaining("总部");var readOnly=new TenantAccessContext(1,"staff","HQ",null,Set.of(),Set.of("core.module.view"),new DataScope("ALL",Set.of()));assertThatThrownBy(()->service.prepare("probe","1.0.0",readOnly,"trace")).hasMessageContaining("总部");}
 @Test void actualPolicyRejectsMissingLicenseSnapshot(){var plan=service.prepare("probe","1.0.0",actor,"trace");var m=new ObjectMapper();try{String json=jdbc.sql("SELECT manifest_json FROM core_module_install_plan WHERE public_id=:id").param("id",plan.publicId()).query(String.class).single();assertThatThrownBy(()->new InstallLicensePolicy(jdbc,clock).check(m.readValue(json,ModulePackageManifest.class))).hasMessageContaining("激活");}catch(Exception e){throw new RuntimeException(e);}}
 private ModulePackageManifest savedManifest()throws Exception{var plan=service.prepare("probe","1.0.0",actor,"trace");return new ObjectMapper().readValue(jdbc.sql("SELECT manifest_json FROM core_module_install_plan WHERE public_id=:id").param("id",plan.publicId()).query(String.class).single(),ModulePackageManifest.class);}
 private void seedLicense(){
  var now=java.sql.Timestamp.from(clock.instant());
  jdbc.sql("INSERT INTO lic_license_snapshot(id,org_no,deployment_instance_id,instance_fingerprint,license_id,schema_version,license_version,raw_license_json,canonical_payload,sha256,signature_algorithm,key_id,signature_value,issued_at,not_before,offline_grace_days,heartbeat_interval_hours,last_platform_time,last_successful_sync_at,platform_instance_status,sync_status) VALUES(1,'ORG','instance','fingerprint','license',2,1,'{}','{}',:sha,'Ed25519','test','test',:now,:now,7,6,:now,:now,'ACTIVE','OK')").param("sha","0".repeat(64)).param("now",now).update();
  jdbc.sql("INSERT INTO lic_module_entitlement(module_code,license_type,status,starts_at,expires_at,perpetual,store_quota,license_version,updated_at) VALUES('probe','FORMAL','ACTIVE',:now,:expires,FALSE,1,1,:now)").param("now",now).param("expires",java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(7)))).update();
 }
 @Test void actualPolicyRejectsExpirationSuspensionAndOfflineReadOnly()throws Exception{
  var m=savedManifest();seedLicense();var real=new InstallLicensePolicy(jdbc,clock);real.check(m);
  jdbc.sql("UPDATE lic_module_entitlement SET expires_at=:date").param("date",java.sql.Timestamp.from(clock.instant().minusSeconds(1))).update();assertThatThrownBy(()->real.check(m)).hasMessageContaining("到期");
  jdbc.sql("UPDATE lic_module_entitlement SET perpetual=TRUE,expires_at=NULL").update();jdbc.sql("UPDATE lic_license_snapshot SET platform_instance_status='SUSPENDED'").update();assertThatThrownBy(()->real.check(m)).hasMessageContaining("同步授权");
  jdbc.sql("UPDATE lic_license_snapshot SET platform_instance_status='ACTIVE',last_successful_sync_at=:date").param("date",java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(8)))).update();assertThatThrownBy(()->real.check(m)).hasMessageContaining("同步授权");
 }
}
