package com.smartelder.core.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.core.api.access.DataScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenantDataScopeServiceTest {

    private JdbcClient jdbc;
    private TenantDataScopeService service;
    private long storeA;
    private long storeB;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:scope_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/tenant").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        service = new TenantDataScopeService(jdbc);
        storeA = insertStore("A", "ACTIVE");
        storeB = insertStore("B", "ACTIVE");
    }

    @Test
    void resolvesExplicitStoresAndRejectsForgedStoreIds() {
        long staffId = insertStaff("HQ", null);
        jdbc.sql("INSERT INTO iam_staff_scope_config (staff_id, scope_type, version, updated_at) "
                        + "VALUES (:staffId, 'STORES', 0, :now)")
                .param("staffId", staffId).param("now", Timestamp.from(Instant.now())).update();
        jdbc.sql("INSERT INTO iam_staff_store_scope (staff_id, store_id) VALUES (:staffId, :storeId)")
                .param("staffId", staffId).param("storeId", storeA).update();

        DataScope scope = service.resolve(staffId, "HQ", null);

        assertThat(scope.type()).isEqualTo("STORES");
        assertThat(scope.storeIds()).containsExactly(storeA);
        service.requireStoreAccess(scope, storeA, false);
        assertThatThrownBy(() -> service.requireStoreAccess(scope, storeB, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_SCOPE_DENIED");
    }

    @Test
    void storeGroupMembershipChangesAffectExistingStaffImmediately() {
        long staffId = insertStaff("HQ", null);
        String groupPublicId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO iam_store_group (public_id, group_code, name, version, created_at, updated_at) "
                        + "VALUES (:publicId, :code, '区域一组', 0, :now, :now)")
                .param("publicId", groupPublicId).param("code", "REGION_" + UUID.randomUUID())
                .param("now", Timestamp.from(Instant.now())).update();
        long groupId = jdbc.sql("SELECT id FROM iam_store_group WHERE public_id = :publicId")
                .param("publicId", groupPublicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_scope_config (staff_id, scope_type, version, updated_at) "
                        + "VALUES (:staffId, 'STORE_GROUP', 0, :now)")
                .param("staffId", staffId).param("now", Timestamp.from(Instant.now())).update();
        jdbc.sql("INSERT INTO iam_staff_store_group_scope (staff_id, store_group_id) VALUES (:staffId, :groupId)")
                .param("staffId", staffId).param("groupId", groupId).update();
        jdbc.sql("INSERT INTO iam_store_group_member (store_group_id, store_id) VALUES (:groupId, :storeId)")
                .param("groupId", groupId).param("storeId", storeA).update();

        assertThat(service.resolve(staffId, "HQ", null).storeIds()).containsExactly(storeA);
        jdbc.sql("DELETE FROM iam_store_group_member WHERE store_id = :storeId").param("storeId", storeA).update();
        jdbc.sql("INSERT INTO iam_store_group_member (store_group_id, store_id) VALUES (:groupId, :storeId)")
                .param("groupId", groupId).param("storeId", storeB).update();
        assertThat(service.resolve(staffId, "HQ", null).storeIds()).containsExactly(storeB);
    }

    @Test
    void storeStaffAlwaysResolvesToHomeStoreAndInactiveStoreIsReadOnly() {
        long staffId = insertStaff("STORE", storeA);
        jdbc.sql("UPDATE core_store SET status = 'SUSPENDED' WHERE id = :id").param("id", storeA).update();

        DataScope scope = service.resolve(staffId, "STORE", storeA);

        assertThat(scope.storeIds()).containsExactly(storeA);
        service.requireStoreAccess(scope, storeA, false);
        assertThatThrownBy(() -> service.requireStoreAccess(scope, storeA, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORE_INACTIVE");
    }

    private long insertStore(String storeNo, String status) {
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO core_store (public_id, store_no, name, status, version, created_at, updated_at) "
                        + "VALUES (:publicId, :storeNo, :name, :status, 0, :now, :now)")
                .param("publicId", publicId).param("storeNo", storeNo + UUID.randomUUID())
                .param("name", storeNo).param("status", status).param("now", now).update();
        return jdbc.sql("SELECT id FROM core_store WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
    }

    private long insertStaff(String type, Long homeStoreId) {
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_staff (public_id, username, password_hash, display_name, staff_type, home_store_id, "
                        + "status, version, created_at, updated_at) VALUES (:publicId, :username, 'hash', '员工', :type, "
                        + ":homeStoreId, 'ACTIVE', 0, :now, :now)")
                .param("publicId", publicId).param("username", UUID.randomUUID().toString())
                .param("type", type).param("homeStoreId", homeStoreId).param("now", now).update();
        return jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
    }
}
