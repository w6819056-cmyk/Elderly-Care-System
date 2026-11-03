package com.smartelder.api.tenant.commerce;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.licensing.*;
import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.core.api.access.*;
import java.util.*;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TenantCommerceServiceTest {
 PlatformLicenseClient client;TenantCommerceService service;
 @BeforeEach void setup(){client=mock(PlatformLicenseClient.class);var factory=new StaticListableBeanFactory();factory.addBean("client",client);var p=new TenantLicenseProperties();p.setPlatformBaseUrl("http://localhost");p.setOrgNo("org");p.setInstancePublicId("ins");p.setInstancePrivateKeyPem("configured");p.setInstanceFingerprint("fingerprint");service=new TenantCommerceService(factory.getBeanProvider(PlatformLicenseClient.class),factory.getBeanProvider(LicenseSyncCoordinator.class),p,new ObjectMapper(),mock(org.springframework.jdbc.core.simple.JdbcClient.class),Clock.systemUTC());}
 TenantAccessContext hq(Set<String> permissions){return new TenantAccessContext(1,"staff","HQ",null,Set.of(),permissions,new DataScope("ALL",Set.of()));}
 @Test void readonlyHeadquartersCanReadButCannotPurchase(){
  var a=hq(Set.of("core.module.view"));assertThat(((Map<?,?>)service.configuration(a)).get("canPurchase")).isEqualTo(false);
  assertThatThrownBy(()->service.call("POST","/orders",Map.of(),a,"trace")).isInstanceOf(TenantStaffAuthException.class);verifyNoInteractions(client);
 }
 @Test void storeCannotUseHeadquartersPurchaseEvenWithPermission(){
  var a=new TenantAccessContext(2,"store","STORE",1L,Set.of(),Set.of("core.module.view","core.module.purchase"),new DataScope("STORES",Set.of(1L)));
  assertThatThrownBy(()->service.call("POST","/orders",Map.of(),a,"trace")).isInstanceOf(TenantStaffAuthException.class);verifyNoInteractions(client);
 }
 @Test void readsOnlyPlatformResponseData()throws Exception{
  when(client.commerce("GET","/catalog",null)).thenReturn(new ObjectMapper().readTree("{\"code\":\"OK\",\"data\":{\"cards\":[]}}"));
  assertThat(service.call("GET","/catalog",null,hq(Set.of("core.module.view")),"trace").path("cards").isArray()).isTrue();
 }
}
