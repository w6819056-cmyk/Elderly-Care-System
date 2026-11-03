package com.smartelder.api.tenant.portal;

import com.smartelder.core.api.access.*;
import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class CustomerManagementServiceTest {
    JdbcClient jdbc;
    CustomerManagementService service;
    TenantAccessContext actor(String type, Long store, Set<Long> scope, boolean all) {
        return new TenantAccessContext(1,"staff",type,store,Set.of(),Set.of("core.customer.view"),new DataScope(all?"ALL":"STORES",scope));
    }
    @BeforeEach void setup() {
        jdbc=JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.sql("CREATE TABLE customer_account(id BIGINT PRIMARY KEY, public_id VARCHAR, username VARCHAR, display_name VARCHAR, created_at TIMESTAMP)").update();
        jdbc.sql("CREATE TABLE core_store(id BIGINT PRIMARY KEY, public_id VARCHAR, store_no VARCHAR, name VARCHAR)").update();
        jdbc.sql("CREATE TABLE core_visit_booking(id BIGINT PRIMARY KEY, public_id VARCHAR, customer_id BIGINT, store_id BIGINT, visitor_name VARCHAR,phone VARCHAR,visit_type VARCHAR,preferred_date DATE,status VARCHAR,remark VARCHAR,created_at TIMESTAMP)").update();
        jdbc.sql("INSERT INTO customer_account VALUES(1,'c1','alice','小王',CURRENT_TIMESTAMP),(2,'c2','bob','小李',CURRENT_TIMESTAMP),(3,'c3','new','新用户',CURRENT_TIMESTAMP)").update();
        jdbc.sql("INSERT INTO core_store VALUES(1,'s1','001','A店'),(2,'s2','002','B店')").update();
        jdbc.sql("INSERT INTO core_visit_booking VALUES(1,'b1',1,1,'王','111111','VISIT',CURRENT_DATE,'PENDING',NULL,CURRENT_TIMESTAMP),(2,'b2',1,1,'王','111111','VISIT',CURRENT_DATE,'COMPLETED',NULL,CURRENT_TIMESTAMP),(3,'b3',1,2,'王','999999','VISIT',CURRENT_DATE,'PENDING','别店备注',CURRENT_TIMESTAMP),(4,'b4',2,2,'李','222222','VISIT',CURRENT_DATE,'PENDING',NULL,CURRENT_TIMESTAMP)").update();
        service=new CustomerManagementService(jdbc);
    }
    @Test void headquartersSeesUnlinkedAccountsAndPaginates() {
        var hq=actor("HQ",null,Set.of(),true);
        assertThat(service.list(hq,"",1,2).total()).isEqualTo(3);
        assertThat(service.list(hq,"",1,2).items()).hasSize(2);
        assertThat(service.list(hq,"",2,2).items()).hasSize(1);
        assertThat(service.list(hq,"新用户",1,20).items()).hasSize(1);
    }
    @Test void storeDeduplicatesAndDoesNotLeakOtherStoreDetailsOrSearch() {
        var a=actor("STORE",1L,Set.of(1L),false);
        var list=service.list(a,"",1,20);
        assertThat(list.total()).isEqualTo(1);
        assertThat(list.items().get(0).bookingCount()).isEqualTo(2);
        assertThat(list.items().get(0).stores()).extracting(CustomerManagementService.Store::name).containsExactly("A店");
        assertThat(service.detail(a,"c1").bookings()).hasSize(2);
        assertThat(service.list(a,"999999",1,20).total()).isZero();
        assertThatThrownBy(()->service.detail(a,"c2")).isInstanceOf(TenantStaffAuthException.class);
    }
    @Test void regionalAndEmptyScopeAreEnforced() {
        assertThat(service.list(actor("HQ",null,Set.of(1L),false),"",1,20).total()).isEqualTo(1);
        assertThat(service.list(actor("HQ",null,Set.of(),false),"",1,20).total()).isZero();
    }
    @Test void requiresDedicatedPermissionAndTreatsWildcardsLiterally() {
        var denied=new TenantAccessContext(1,"staff","HQ",null,Set.of(),Set.of("core.visit.view"),new DataScope("ALL",Set.of()));
        assertThatThrownBy(()->service.list(denied,"",1,20)).isInstanceOf(TenantStaffAuthException.class);
        assertThat(service.list(actor("HQ",null,Set.of(),true),"%",1,20).total()).isZero();
        assertThatThrownBy(()->service.list(actor("HQ",null,Set.of(),true),"",0,20)).isInstanceOf(IllegalArgumentException.class);
    }
}
