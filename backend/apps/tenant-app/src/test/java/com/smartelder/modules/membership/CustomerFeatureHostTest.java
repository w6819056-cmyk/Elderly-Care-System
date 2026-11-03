package com.smartelder.modules.membership;
import com.smartelder.api.tenant.portal.*;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.module.api.*;
import com.smartelder.module.packaging.ModulePackageManifest;
import com.smartelder.module.runtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerFeatureHostTest {
 @Test void passesOnlyAuthenticatedIdentityAndHidesDisabledModules(){
  var jdbc=JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:features_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  jdbc.sql("CREATE TABLE lic_license_snapshot(id INT,platform_instance_status VARCHAR(30))").update();jdbc.sql("INSERT INTO lic_license_snapshot VALUES(1,'ACTIVE')").update();
  jdbc.sql("CREATE TABLE lic_module_entitlement(module_code VARCHAR(40),status VARCHAR(30),starts_at TIMESTAMP)").update();jdbc.sql("INSERT INTO lic_module_entitlement VALUES('membership','ACTIVE',TIMESTAMP '2020-01-01 00:00:00')").update();jdbc.sql("CREATE TABLE core_enterprise_module(module_code VARCHAR(40),enabled BOOLEAN)").update();jdbc.sql("INSERT INTO core_enterprise_module VALUES('membership',TRUE)").update();
  var customers=mock(CustomerService.class);when(customers.resolve("token")).thenReturn(7L);when(customers.me(7)).thenReturn(new CustomerService.Profile("self-customer","self","本人"));
  AtomicReference<String> received=new AtomicReference<>();CustomerFeatureProvider provider=new CustomerFeatureProvider(){public String moduleCode(){return "membership";}public Object get(String id){received.set(id);return Map.of("member",true,"levelName","安心会员");}};
  var manifest=new ModulePackageManifest(1,"test",new ModuleDescriptor("membership","会员","1.0.0",10,List.of(),Set.of()),"2.0.0","3.0.0","com.smartelder.modules.membership.Entry",Map.of(),List.of(),Map.of("backend/module.jar","0".repeat(64)));
  var state=new RuntimeState(new ModuleRuntimeFiles(Path.of("unused"),Path.of("unused"),Path.of("unused")),new ModuleRuntimeLoader(),List.of(new ModuleRuntimeLoader.Loaded(manifest,new ModuleRuntimeFiles.Entry(UUID.randomUUID().toString(),"0".repeat(64)),List.of(),Map.of())));
  var controller=new CustomerFeaturesController(customers,List.of(provider),jdbc,state);var request=new MockHttpServletRequest();request.addParameter("customerPublicId","someone-else");
  Object result=controller.current("token",request);assertThat(received.get()).isEqualTo("self-customer");assertThat(result.toString()).contains("membership");
  received.set(null);jdbc.sql("UPDATE core_enterprise_module SET enabled=FALSE").update();controller.current("token",request);assertThat(received.get()).isNull();
 }
}
