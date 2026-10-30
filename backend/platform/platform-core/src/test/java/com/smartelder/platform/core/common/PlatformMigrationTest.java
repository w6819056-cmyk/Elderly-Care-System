package com.smartelder.platform.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformMigrationTest {

    @Test
    void createsControlPlaneSchemaAndSeedsPermissions() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("platform_migration")) {
            Long tableCount = database.jdbcClient().sql("""
                            SELECT COUNT(*)
                            FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name LIKE 'pf_%'
                            """)
                    .query(Long.class)
                    .single();
            List<String> permissions = database.jdbcClient().sql("""
                            SELECT code FROM pf_admin_permission ORDER BY code
                            """)
                    .query(String.class)
                    .list();

            assertThat(tableCount).isEqualTo(26L);
            assertThat(database.jdbcClient().sql("""
                            SELECT COUNT(*) FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name = 'pf_instance_nonce'
                            """).query(Long.class).single()).isEqualTo(1L);
            assertThat(permissions).contains(
                    "platform.account.manage",
                    "platform.enterprise.manage",
                    "platform.module.manage",
                    "platform.plan.manage",
                    "platform.order.manage",
                    "platform.license.manage",
                    "platform.audit.view"
            );
        }
    }
}
