package com.smartelder.platform.core.common;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public class PlatformAuditService {

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;

    public PlatformAuditService(JdbcClient jdbcClient, PlatformClock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void record(
            Long actorUserId,
            String actorPublicId,
            String action,
            String targetType,
            String targetPublicId,
            String detailJson,
            String traceId
    ) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");

        jdbcClient.sql("""
                        INSERT INTO pf_audit_log(
                            actor_user_id, actor_public_id, action, target_type,
                            target_public_id, detail_json, trace_id, created_at
                        ) VALUES (
                            :actorUserId, :actorPublicId, :action, :targetType,
                            :targetPublicId, :detailJson, :traceId, :createdAt
                        )
                        """)
                .param("actorUserId", actorUserId, Types.BIGINT)
                .param("actorPublicId", actorPublicId, Types.VARCHAR)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetPublicId", targetPublicId, Types.VARCHAR)
                .param("detailJson", detailJson, Types.VARCHAR)
                .param("traceId", traceId)
                .param("createdAt", Timestamp.from(clock.now()))
                .update();
    }
}
