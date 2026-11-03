package com.smartelder.modules.membership;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.api.tenant.portal.CustomerManagementFeatureService;
import com.smartelder.api.tenant.portal.CustomerManagementService;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.core.api.access.*;
import com.smartelder.module.api.CustomerManagementFeatureProvider;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.packaging.ModulePackageManifest;
import com.smartelder.module.runtime.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CustomerManagementFeatureServiceTest {
    JdbcClient jdbc;
    CustomerManagementService customers;
    CustomerManagementFeatureService service;
    CountingProvider provider;
    TenantAccessContext hq;
    TenantAccessContext store;
    Clock clock=Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"),ZoneOffset.UTC);

    @BeforeEach void setup() {
        jdbc=JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.sql("CREATE TABLE customer_account(id BIGINT PRIMARY KEY, public_id VARCHAR, username VARCHAR, display_name VARCHAR, created_at TIMESTAMP)").update();
        jdbc.sql("CREATE TABLE core_store(id BIGINT PRIMARY KEY, public_id VARCHAR, store_no VARCHAR, name VARCHAR)").update();
        jdbc.sql("CREATE TABLE core_visit_booking(id BIGINT PRIMARY KEY, public_id VARCHAR, customer_id BIGINT, store_id BIGINT, visitor_name VARCHAR,phone VARCHAR,visit_type VARCHAR,preferred_date DATE,status VARCHAR,remark VARCHAR,created_at TIMESTAMP)").update();
        jdbc.sql("INSERT INTO customer_account VALUES(1,'c1','alice','小王',CURRENT_TIMESTAMP),(2,'c2','bob','小李',CURRENT_TIMESTAMP)").update();
        jdbc.sql("INSERT INTO core_store VALUES(1,'s1','001','A店'),(2,'s2','002','B店')").update();
        jdbc.sql("INSERT INTO core_visit_booking VALUES(1,'b1',1,1,'王','111111','VISIT',CURRENT_DATE,'PENDING',NULL,CURRENT_TIMESTAMP),(2,'b2',2,2,'李','222222','VISIT',CURRENT_DATE,'PENDING',NULL,CURRENT_TIMESTAMP)").update();
        jdbc.sql("CREATE TABLE lic_license_snapshot(id INT PRIMARY KEY,platform_instance_status VARCHAR(30))").update();
        jdbc.sql("INSERT INTO lic_license_snapshot VALUES(1,'ACTIVE')").update();
        jdbc.sql("CREATE TABLE lic_module_entitlement(module_code VARCHAR(40),status VARCHAR(30),starts_at TIMESTAMP,expires_at TIMESTAMP)").update();
        jdbc.sql("INSERT INTO lic_module_entitlement VALUES('membership','ACTIVE',TIMESTAMP '2020-01-01 00:00:00',NULL)").update();
        jdbc.sql("CREATE TABLE core_enterprise_module(module_code VARCHAR(40),enabled BOOLEAN)").update();
        jdbc.sql("INSERT INTO core_enterprise_module VALUES('membership',TRUE)").update();
        customers=new CustomerManagementService(jdbc);
        var manifest=new ModulePackageManifest(1,"test",new ModuleDescriptor("membership","会员","1.0.0",10,List.of(),Set.of("membership:member:read")),"2.1.0","3.0.0","com.smartelder.modules.membership.Entry",Map.of(),List.of(),Map.of("backend/module.jar","0".repeat(64)));
        var runtime=new RuntimeState(new ModuleRuntimeFiles(Path.of("unused"),Path.of("unused"),Path.of("unused")),new ModuleRuntimeLoader(),List.of(new ModuleRuntimeLoader.Loaded(manifest,new ModuleRuntimeFiles.Entry(UUID.randomUUID().toString(),"0".repeat(64)),List.of(),Map.of())));
        provider=new CountingProvider();
        hq=actor("HQ",null,Set.of(1L),Set.of("core.customer.view","membership:member:read"));
        store=actor("STORE",1L,Set.of(1L),Set.of("core.customer.view","membership:member:read"));
        service=new CustomerManagementFeatureService(customers,List.of(provider),jdbc,runtime,clock);
    }

    @Test void returnsFeatureOnlyForVisibleAuthorizedHqCustomer() {
        assertThat(service.features(hq,"c1")).containsExactly(new CustomerManagementFeatureService.Feature("membership",Map.of("member",true,"levelName","安心会员")));
        assertThatThrownBy(()->service.features(hq,"c2")).isInstanceOf(TenantStaffAuthException.class).hasMessageContaining("不在管理范围内");
        assertThat(provider.requested).containsExactly("c1");
    }

    @Test void missingModulePermissionOmitsFeature() {
        assertThat(service.features(actor("HQ",null,Set.of(1L),Set.of("core.customer.view")),"c1")).isEmpty();
        assertThat(provider.requested).isEmpty();
    }

    @Test void storeCannotUseHeadquartersFeatureEndpoint() {
        assertThatThrownBy(()->service.features(store,"c1")).isInstanceOf(TenantStaffAuthException.class);
        assertThat(provider.requested).isEmpty();
    }

    @Test void disabledExpiredOrUnlicensedModuleIsOmitted() {
        jdbc.sql("UPDATE lic_module_entitlement SET expires_at=:date").param("date",Timestamp.from(Instant.parse("2026-02-01T00:00:00Z"))).update();
        assertThat(service.features(hq,"c1")).isEmpty();
        jdbc.sql("UPDATE lic_module_entitlement SET expires_at=NULL").update();
        jdbc.sql("UPDATE core_enterprise_module SET enabled=FALSE").update();
        assertThat(service.features(hq,"c1")).isEmpty();
        jdbc.sql("UPDATE core_enterprise_module SET enabled=TRUE").update();
        jdbc.sql("UPDATE lic_license_snapshot SET platform_instance_status='SUSPENDED'").update();
        assertThat(service.features(hq,"c1")).isEmpty();
        assertThat(provider.requested).isEmpty();
    }

    @Test void providerFailureDoesNotBreakBaseCustomerDetail() {
        provider.fail=true;
        assertThat(service.features(hq,"c1")).isEmpty();
    }

    private TenantAccessContext actor(String type,Long home,Set<Long> stores,Set<String> permissions){return new TenantAccessContext(1,"staff",type,home,Set.of(),permissions,new DataScope(type.equals("HQ")&&stores.size()==1?"STORES":type.equals("HQ")?"ALL":"STORES",stores));}

    static class CountingProvider implements CustomerManagementFeatureProvider {
        final List<String> requested=new ArrayList<>();boolean fail;
        public String moduleCode(){return "membership";}
        public String requiredPermission(){return "membership:member:read";}
        public Object get(String id){requested.add(id);if(fail)throw new IllegalStateException("private provider detail");return Map.of("member",true,"levelName","安心会员");}
    }
}
