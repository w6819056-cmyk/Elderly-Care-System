package com.smartelder.core.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleMenuEntry;
import com.smartelder.module.api.ModuleRegistry;
import com.smartelder.module.api.ModuleResourceOperation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ModuleAccessQueryTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private final ModuleRegistry registry = new ModuleRegistry(List.of(new ModuleDescriptor("membership", "会员", "1.0.0", 10,
            List.of(), Set.of("membership:member:read", "membership:member:write", "membership:other:write"),
            List.of(new ModuleMenuEntry("membership", "会员", "/membership", "users", "membership:member:read")),
            Set.of(new ModuleResourceOperation("MemberResource", "read", "membership:member:read", true, false),
                    new ModuleResourceOperation("MemberResource", "write", "membership:member:write", true, true),
                    new ModuleResourceOperation("OtherResource", "write", "membership:other:write", true, true)),
            Set.of("MemberResource#write"))));

    @Test
    void enforcesOfflineAndWindDownBoundariesAndHonorsRegisteredWindDownWrites() {
        Fixture f = fixture(NOW.plusSeconds(10 * 86400L), NOW);
        assertThat(f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW).state()).isEqualTo("ACTIVE");
        assertThat(f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW.plusSeconds(7 * 86400L)).allowed()).isTrue();
        assertThat(f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW.plusSeconds(7 * 86400L).plusNanos(1)).state())
                .isEqualTo("OFFLINE_READ_ONLY");

        f.expireAt(Instant.parse("2026-10-28T00:00:00Z"));
        var boundary = f.query.evaluate("membership", "MemberResource", "write", f.storeId, NOW);
        assertThat(boundary.state()).isEqualTo("WIND_DOWN");
        assertThat(boundary.allowed()).isTrue();
        var beyond = f.query.evaluate("membership", "MemberResource", "write", f.storeId, NOW.plusNanos(1));
        assertThat(beyond.state()).isEqualTo("READ_ONLY");
        assertThat(beyond.allowed()).isFalse();
    }

    @Test
    void disabledModuleRemainsBlockedDuringWindDown() {
        Fixture f = fixture(NOW.minusSeconds(1 * 86400L), NOW);
        f.jdbc.sql("UPDATE core_enterprise_module SET enabled=FALSE WHERE module_code='membership'").update();
        var decision = f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW);
        assertThat(decision.state()).isEqualTo("DISABLED");
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void readOnlyStatesStillRequireAnActiveStoreAssignment() {
        Fixture f = fixture(NOW.plusSeconds(10 * 86400L), NOW.minusSeconds(8 * 86400L));
        f.jdbc.sql("UPDATE core_store_module_assignment SET status='REVOKED' WHERE module_code='membership'").update();

        var offline = f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW);

        assertThat(offline.state()).isEqualTo("NOT_ASSIGNED");
        assertThat(offline.allowed()).isFalse();
        assertThat(offline.reasonCode()).isEqualTo("MODULE_NOT_ASSIGNED");
    }

    @Test
    void inactiveStoreCannotPerformCoreManagementWrites() {
        Fixture f = fixture(NOW.plusSeconds(10 * 86400L), NOW);
        f.jdbc.sql("UPDATE core_store SET status='CLOSED' WHERE id=:id").param("id", f.storeId).update();

        var decision = f.query.evaluate("core", "StoreResource", "core.store.manage", f.storeId, NOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("STORE_NOT_ACTIVE");
    }

    @Test
    void permanentGrantDoesNotExpireByDate() {
        Fixture f = fixture(NOW.minusSeconds(30 * 86400L), NOW.plusSeconds(10 * 86400L));
        f.jdbc.sql("UPDATE lic_module_entitlement SET perpetual=TRUE, expires_at=NULL WHERE module_code='membership'").update();

        var decision = f.query.evaluate("membership", "MemberResource", "write", f.storeId,
                NOW.plusSeconds(10 * 86400L));

        assertThat(decision.state()).isEqualTo("ACTIVE");
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void revokedInstanceBlocksModuleAccessImmediately() {
        Fixture f = fixture(NOW.plusSeconds(10 * 86400L), NOW);
        f.jdbc.sql("UPDATE lic_license_snapshot SET platform_instance_status='REVOKED' WHERE id=1").update();

        var decision = f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("ENTERPRISE_SUSPENDED");
    }

    @Test
    void successfulPlatformRecoveryRestoresModuleAccess() {
        Fixture f = fixture(NOW.plusSeconds(10 * 86400L), NOW);
        f.jdbc.sql("UPDATE lic_license_snapshot SET platform_instance_status='SUSPENDED' WHERE id=1").update();
        assertThat(f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW).allowed()).isFalse();

        f.jdbc.sql("UPDATE lic_license_snapshot SET platform_instance_status='ACTIVE', last_successful_sync_at=:now WHERE id=1")
                .param("now", Timestamp.from(NOW)).update();

        var recovered = f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW);
        assertThat(recovered.state()).isEqualTo("ACTIVE");
        assertThat(recovered.allowed()).isTrue();
    }

    @Test
    void windDownWritePermissionIsScopedToItsResource() {
        Fixture f = fixture(NOW.minusSeconds(1 * 86400L), NOW);
        var otherResource = f.query.evaluate("membership", "OtherResource", "write", f.storeId, NOW);

        assertThat(otherResource.allowed()).isFalse();
        assertThat(otherResource.state()).isEqualTo("WIND_DOWN");
        assertThat(otherResource.reasonCode()).isEqualTo("MODULE_READ_ONLY");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lifecycleCases")
    void lifecycleMatrixAppliesExpectedReadAndWriteAccessWithoutDeletingBusinessData(LifecycleCase scenario) {
        Fixture f = fixture(NOW.plusSeconds(10 * 86400L), NOW);
        f.jdbc.sql("CREATE TABLE mem_member_probe(public_id VARCHAR(36) PRIMARY KEY, customer_public_id VARCHAR(36) NOT NULL)").update();
        f.jdbc.sql("INSERT INTO mem_member_probe VALUES('member-1','customer-1')").update();
        scenario.change().accept(f);

        var read = f.query.evaluate("membership", "MemberResource", "read", f.storeId, NOW);
        var write = f.query.evaluate("membership", "MemberResource", "write", f.storeId, NOW);

        assertThat(read.state()).as("read state").isEqualTo(scenario.expectedState());
        assertThat(read.allowed()).as("read access").isEqualTo(scenario.readAllowed());
        assertThat(write.state()).as("write state").isEqualTo(scenario.expectedState());
        assertThat(write.allowed()).as("write access").isEqualTo(scenario.writeAllowed());
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM mem_member_probe WHERE public_id='member-1'").query(Integer.class).single())
                .as("existing module data is retained across lifecycle changes").isEqualTo(1);
    }

    private static java.util.stream.Stream<LifecycleCase> lifecycleCases() {
        return java.util.stream.Stream.of(
                new LifecycleCase("active entitlement, enabled, assigned", f -> {}, "ACTIVE", true, true),
                new LifecycleCase("revoked entitlement", f -> f.jdbc.sql("UPDATE lic_module_entitlement SET status='REVOKED' WHERE module_code='membership'").update(), "UNLICENSED", false, false),
                new LifecycleCase("disabled module", f -> f.jdbc.sql("UPDATE core_enterprise_module SET enabled=FALSE WHERE module_code='membership'").update(), "DISABLED", false, false),
                new LifecycleCase("revoked store assignment", f -> f.jdbc.sql("UPDATE core_store_module_assignment SET status='REVOKED' WHERE module_code='membership'").update(), "NOT_ASSIGNED", false, false),
                new LifecycleCase("offline grace elapsed", f -> f.jdbc.sql("UPDATE lic_license_snapshot SET last_successful_sync_at=:sync WHERE id=1")
                        .param("sync", Timestamp.from(NOW.minusSeconds(8 * 86400L))).update(), "OFFLINE_READ_ONLY", true, false),
                new LifecycleCase("three-day wind-down", f -> f.expireAt(NOW.minusSeconds(2 * 86400L)), "WIND_DOWN", true, true),
                new LifecycleCase("wind-down ended", f -> f.expireAt(NOW.minusSeconds(4 * 86400L)), "READ_ONLY", true, false),
                new LifecycleCase("not-yet-started entitlement", f -> f.jdbc.sql("UPDATE lic_module_entitlement SET starts_at=:start WHERE module_code='membership'")
                        .param("start", Timestamp.from(NOW.plusSeconds(1))).update(), "UNLICENSED", false, false));
    }

    private record LifecycleCase(String name, java.util.function.Consumer<Fixture> change,
            String expectedState, boolean readAllowed, boolean writeAllowed) {
        @Override public String toString() { return name; }
    }

    private Fixture fixture(Instant expiresAt, Instant syncedAt) {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:access_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/tenant").load().migrate();
        JdbcClient jdbc = JdbcClient.create(ds);
        Timestamp now = Timestamp.from(NOW);
        jdbc.sql("INSERT INTO core_enterprise(id,public_id,org_no,name,status,created_at,updated_at) VALUES(1,'ent','ORG','E','ACTIVE',:n,:n)")
                .param("n", now).update();
        jdbc.sql("INSERT INTO iam_staff(id,public_id,username,password_hash,display_name,staff_type,status,created_at,updated_at) "
                + "VALUES(1,'staff','hq','hash','HQ','HQ','ACTIVE',:n,:n)").param("n", now).update();
        jdbc.sql("INSERT INTO core_store(public_id,store_no,name,status,created_at,updated_at) VALUES('store','S1','Store','ACTIVE',:n,:n)")
                .param("n", now).update();
        long storeId = jdbc.sql("SELECT id FROM core_store WHERE public_id='store'").query(Long.class).single();
        jdbc.sql("INSERT INTO lic_license_snapshot(id,org_no,deployment_instance_id,instance_fingerprint,license_id,schema_version,license_version," +
                "raw_license_json,canonical_payload,sha256,signature_algorithm,key_id,signature_value,issued_at,not_before,offline_grace_days," +
                "heartbeat_interval_hours,last_platform_time,last_successful_sync_at,platform_instance_status,sync_status) " +
                "VALUES(1,'ORG','ins','fingerprint','license',2,1,'{}','{}',:digest,'Ed25519','key','signature',:n,:n,7,6,:sync,:sync,'ACTIVE','CURRENT')")
                .param("digest", "a".repeat(64)).param("n", now).param("sync", Timestamp.from(syncedAt)).update();
        jdbc.sql("INSERT INTO lic_module_entitlement(module_code,license_type,status,starts_at,expires_at,perpetual,store_quota,license_version,updated_at) "
                + "VALUES('membership','FORMAL','ACTIVE',:n,:expires,FALSE,1,1,:n)")
                .param("n", now).param("expires", Timestamp.from(expiresAt)).update();
        jdbc.sql("INSERT INTO core_enterprise_module(module_code,enabled,updated_by_staff_id,updated_at) VALUES('membership',TRUE,1,:n)")
                .param("n", now).update();
        jdbc.sql("INSERT INTO core_store_module_assignment(public_id,module_code,store_id,status,assigned_by_staff_id,assigned_at) "
                + "VALUES('assignment','membership',:store,'ACTIVE',1,:n)").param("store", storeId).param("n", now).update();
        return new Fixture(jdbc, new ModuleAccessQuery(jdbc, registry), storeId);
    }
    private record Fixture(JdbcClient jdbc, ModuleAccessQuery query, long storeId) {
        void expireAt(Instant value) {
            jdbc.sql("UPDATE lic_module_entitlement SET expires_at=:expires WHERE module_code='membership'")
                    .param("expires", Timestamp.from(value)).update();
        }
    }
}
