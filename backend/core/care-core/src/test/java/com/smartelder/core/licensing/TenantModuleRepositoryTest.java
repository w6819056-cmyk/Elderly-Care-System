package com.smartelder.core.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartelder.core.licensing.internal.JdbcTenantModuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

class TenantModuleRepositoryTest {
    @Test
    void concurrentAssignmentsCannotExceedSignedQuota() throws Exception {
        Fixture f = fixture(3, 12);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final int store = i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    f.repository.assign("membership", "store-" + store, "key-" + store, 1, "trace-" + store);
                    return true;
                } catch (TenantModuleException ex) {
                    if (!"MODULE_QUOTA_EXCEEDED".equals(ex.code())) throw ex;
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (var result : results) if (result.get()) succeeded++;
        pool.shutdown();
        assertThat(succeeded).isEqualTo(3);
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM core_store_module_assignment WHERE status='ACTIVE'")
                .query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void explicitRevocationKeepsHistoryAndFreesOneSlot() {
        Fixture f = fixture(1, 2);
        var first = f.repository.assign("membership", "store-0", "first-key", 1, "trace-1");
        f.repository.revoke("membership", "store-0", 1, "trace-2");
        var second = f.repository.assign("membership", "store-1", "second-key", 1, "trace-3");
        assertThat(second.storePublicId()).isEqualTo("store-1");
        assertThat(f.repository.listAssignments("membership")).extracting(TenantModuleRepository.AssignmentView::status)
                .containsExactly("REVOKED", "ACTIVE");
        assertThat(first.publicId()).isNotEqualTo(second.publicId());
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM audit_tenant_log WHERE action IN ('MODULE_STORE_ASSIGNED','MODULE_STORE_REVOKED')")
                .query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void identicalIdempotencyKeyReturnsOriginalAssignment() {
        Fixture f = fixture(1, 1);
        var first = f.repository.assign("membership", "store-0", "same-key", 1, "trace-1");
        var repeated = f.repository.assign("membership", "store-0", "same-key", 1, "trace-2");
        assertThat(repeated.publicId()).isEqualTo(first.publicId());
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM core_store_module_assignment").query(Long.class).single()).isEqualTo(1);
    }

    private static Fixture fixture(int quota, int stores) {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:mod_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/tenant").load().migrate();
        JdbcClient jdbc = JdbcClient.create(ds);
        var now = java.sql.Timestamp.from(Instant.parse("2026-10-31T00:00:00Z"));
        jdbc.sql("INSERT INTO core_enterprise(id,public_id,org_no,name,status,created_at,updated_at) VALUES(1,'ent','ORG','E','ACTIVE',:n,:n)")
                .param("n", now).update();
        jdbc.sql("INSERT INTO iam_staff(id,public_id,username,password_hash,display_name,staff_type,status,created_at,updated_at) "
                + "VALUES(1,'staff','hq','hash','HQ','HQ','ACTIVE',:n,:n)").param("n", now).update();
        for (int i = 0; i < stores; i++) {
            jdbc.sql("INSERT INTO core_store(public_id,store_no,name,status,created_at,updated_at) "
                    + "VALUES(:id,:no,:name,'ACTIVE',:n,:n)")
                    .param("id", "store-" + i).param("no", "S" + i).param("name", "Store " + i).param("n", now).update();
        }
        jdbc.sql("INSERT INTO lic_module_entitlement(module_code,license_type,status,starts_at,perpetual,store_quota,license_version,updated_at) "
                + "VALUES('membership','FORMAL','ACTIVE',:n,TRUE,:q,1,:n)").param("n", now).param("q", quota).update();
        return new Fixture(jdbc, new JdbcTenantModuleRepository(jdbc, Clock.fixed(now.toInstant(), ZoneOffset.UTC),
                new JdbcTransactionManager(ds)));
    }
    private record Fixture(JdbcClient jdbc, JdbcTenantModuleRepository repository) {}
}
