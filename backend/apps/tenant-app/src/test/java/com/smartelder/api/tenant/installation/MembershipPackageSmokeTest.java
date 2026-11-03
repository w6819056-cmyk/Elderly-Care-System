package com.smartelder.api.tenant.installation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.TenantApiExceptionHandler;
import com.smartelder.core.api.access.*;
import com.smartelder.core.api.customer.CustomerQueryApi;
import com.smartelder.module.api.CustomerFeatureProvider;
import com.smartelder.module.packaging.*;
import com.smartelder.module.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="care.membership.package",matches=".+")
class MembershipPackageSmokeTest {
 @TempDir Path directory;
 @Test void signedExternalPackageRunsActualMembershipLifecycleWithoutBundlingImplementation()throws Exception{
  byte[] bytes=Files.readAllBytes(Path.of(System.getProperty("care.membership.package")));
  var verified=new ModulePackageVerifier(TrustedPublisherKeys.load(Path.of(System.getProperty("care.membership.trust")))).verify(bytes);
  Path cache=directory.resolve("cache");Files.createDirectories(cache);Files.write(cache.resolve(verified.packageSha256()+".caremod"),bytes);
  var files=new ModuleRuntimeFiles(directory.resolve("runtime"),cache,Path.of(System.getProperty("care.membership.trust")));files.extract(verified);files.index(new ModuleRuntimeFiles.Index(1,Map.of("membership",new ModuleRuntimeFiles.Entry(UUID.randomUUID().toString(),verified.packageSha256()))));
  var ds=new DriverManagerDataSource("jdbc:h2:mem:package_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  try(var connection=ds.getConnection()){for(String path:verified.manifest().migrations())ScriptUtils.executeSqlScript(connection,new EncodedResource(new ByteArrayResource(verified.file(path)),"UTF-8"));}
  String customer=UUID.randomUUID().toString();var staff=new AtomicReference<>(new TenantAccessContext(1,UUID.randomUUID().toString(),"HQ",null,Set.of(),Set.of(),new DataScope("ALL",Set.of())));
  CustomerQueryApi identities=new CustomerQueryApi(){public Optional<CustomerSummary> find(String id){return id.equals(customer)?Optional.of(new CustomerSummary(customer,"package-user","验证客户")):Optional.empty();}public List<CustomerSummary> search(String q,int limit){return find(customer).stream().toList();}};
  try(var loader=new ModuleRuntimeLoader();var context=new AnnotationConfigApplicationContext()){
   var loaded=loader.load(files,"2.1.0",getClass().getClassLoader());assertThat(loaded).hasSize(1);
   context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(ds));context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));context.registerBean(CustomerQueryApi.class,()->identities);context.registerBean(StaffAccessApi.class,()->staff::get);
   loaded.get(0).configurations().forEach(context::register);context.refresh();
   var mvc=MockMvcBuilders.standaloneSetup(context.getBeansWithAnnotation(RestController.class).values().toArray()).setControllerAdvice(new TenantApiExceptionHandler()).build();var mapper=new ObjectMapper();String base="/api/tenant/v1/modules/membership";
   var created=mvc.perform(post(base+"/levels").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"安心会员\",\"rank\":1,\"benefits\":\"专属咨询说明\",\"enabled\":true}")).andExpect(status().isOk()).andReturn();String level=mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
   mvc.perform(post(base+"/members").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("customerPublicId",customer,"levelId",level,"reason","隔离环境验证开通")))).andExpect(status().isOk());
   var provider=context.getBean(CustomerFeatureProvider.class);assertThat(((Map<?,?>)provider.get(customer)).get("member")).isEqualTo(true);
   mvc.perform(put(base+"/levels/"+level).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"安心会员\",\"rank\":1,\"benefits\":\"专属咨询说明\",\"enabled\":false,\"version\":0}")).andExpect(status().isOk());
   assertThat(((Map<?,?>)provider.get(customer)).get("levelEnabled")).isEqualTo(false);
   staff.set(new TenantAccessContext(2,UUID.randomUUID().toString(),"STORE",1L,Set.of(),Set.of(),new DataScope("STORES",Set.of(1L))));
   mvc.perform(get(base+"/members")).andExpect(status().isForbidden());mvc.perform(get(base+"/levels")).andExpect(status().isForbidden());mvc.perform(get(base+"/lookup").param("customerPublicId",customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.member").value(true));
  }
 }
}
