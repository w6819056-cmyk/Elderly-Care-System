package com.smartelder.core.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenantMigrationTest {

    private static final Set<String> REQUIRED_TABLES = Set.of(
            "core_enterprise", "core_store", "iam_staff", "iam_permission_definition",
            "iam_permission_group", "iam_permission_group_item", "iam_role",
            "iam_role_permission_group", "iam_role_permission", "iam_staff_role",
            "iam_staff_store_scope", "iam_store_group", "iam_store_group_member",
            "audit_tenant_log", "iam_staff_scope_config", "iam_staff_store_group_scope",
            "lic_license_snapshot", "lic_module_entitlement", "core_enterprise_module",
            "core_store_module_assignment", "lic_sync_attempt");

    @Test
    void migratesEmptyTenantDatabaseAndSeedsUniqueCorePermissions() {
        String database = "tenant_migration_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/tenant")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Set<String> actualTables = Set.copyOf(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class));
        assertThat(actualTables).containsAll(REQUIRED_TABLES);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("3");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_permission_definition WHERE permission_code IN "
                        + "('core.enterprise.view', 'core.enterprise.manage', 'core.store.view', 'core.store.manage', "
                        + "'iam.staff.view', 'iam.staff.manage', 'iam.role.view', 'iam.role.manage', "
                        + "'iam.permission-group.view', 'iam.permission-group.manage', 'core.module.view', "
                        + "'core.module.manage', 'core.module.assignment.manage')",
                Integer.class);
        Integer distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT permission_code) FROM iam_permission_definition",
                Integer.class);
        assertThat(count).isEqualTo(13);
        assertThat(distinct).isEqualTo(count);
    }
}
