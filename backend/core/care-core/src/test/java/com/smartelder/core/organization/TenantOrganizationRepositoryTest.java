package com.smartelder.core.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartelder.core.api.access.DataScope;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.core.organization.internal.JdbcTenantOrganizationRepository;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenantOrganizationRepositoryTest {

    @Test
    void persistsStoreTransitionsWithVersionedAuditEntries() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:org_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/tenant").load().migrate();
        JdbcTenantOrganizationRepository repository = new JdbcTenantOrganizationRepository(JdbcClient.create(dataSource));
        TenantOrganizationService service = new TenantOrganizationService(repository);
        TenantAccessContext actor = new TenantAccessContext(7L, "hq-test", "HQ", null, Set.of("HQ_ADMIN"),
                Set.of("core.store.manage", "core.store.view"), new DataScope("ALL", Set.of()));
        String storeNo = "S-" + UUID.randomUUID();

        TenantOrganizationService.StoreView store = service.createStore(
                new TenantOrganizationService.CreateStoreCommand(storeNo, "测试门店", "CN-01", "地址"),
                actor, "trace-create");
        TenantOrganizationService.StoreView active = service.changeStoreStatus(
                store.publicId(), "ACTIVE", actor, "trace-activate");

        assertThat(active.version()).isEqualTo(1);
        assertThat(active.operational()).isTrue();
        assertThat(JdbcClient.create(dataSource).sql(
                "SELECT COUNT(*) FROM audit_tenant_log WHERE target_public_id = :publicId")
                .param("publicId", store.publicId()).query(Integer.class).single()).isEqualTo(2);
    }
}
