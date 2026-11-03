package com.smartelder.platform.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PlatformAuditServiceTest {

    @Test
    void recordsAuditEntryInPlatformControlDatabase() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("platform_audit")) {
            PlatformClock clock = new PlatformClock(
                    Clock.fixed(Instant.parse("2026-10-30T02:03:04Z"), ZoneOffset.UTC)
            );
            PlatformAuditService service = new PlatformAuditService(database.jdbcClient(), clock);

            service.record(
                    7L,
                    "adm_01J0000000000000000000000",
                    "enterprise.activated",
                    "enterprise",
                    "ent_01J0000000000000000000000",
                    "{\"instance\":\"ins_01J0000000000000000000000\"}",
                    "tr_audit_test"
            );

            AuditRow row = database.jdbcClient().sql("""
                            SELECT actor_user_id, actor_public_id, action, target_type,
                                   target_public_id, detail_json, trace_id, created_at
                            FROM pf_audit_log
                            """)
                    .query((rs, rowNum) -> new AuditRow(
                            rs.getLong("actor_user_id"),
                            rs.getString("actor_public_id"),
                            rs.getString("action"),
                            rs.getString("target_type"),
                            rs.getString("target_public_id"),
                            rs.getString("detail_json"),
                            rs.getString("trace_id"),
                            rs.getTimestamp("created_at")
                    ))
                    .single();

            assertThat(row.actorUserId()).isEqualTo(7L);
            assertThat(row.actorPublicId()).isEqualTo("adm_01J0000000000000000000000");
            assertThat(row.action()).isEqualTo("enterprise.activated");
            assertThat(row.targetType()).isEqualTo("enterprise");
            assertThat(row.targetPublicId()).isEqualTo("ent_01J0000000000000000000000");
            assertThat(row.detailJson()).contains("ins_01J");
            assertThat(row.traceId()).isEqualTo("tr_audit_test");
            assertThat(row.createdAt()).isNotNull();
        }
    }

    private record AuditRow(
            long actorUserId,
            String actorPublicId,
            String action,
            String targetType,
            String targetPublicId,
            String detailJson,
            String traceId,
            java.sql.Timestamp createdAt
    ) {
    }
}
