package com.smartelder.platform.core.enterprise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class HeartbeatService {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofHours(6);

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final LicenseSigningService licenseSigningService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public HeartbeatService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            LicenseSigningService licenseSigningService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.licenseSigningService = Objects.requireNonNull(
                licenseSigningService,
                "licenseSigningService must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public HeartbeatResult heartbeat(
            InstanceRequestVerifier.VerifiedInstance instance,
            HeartbeatCommand command,
            String remoteAddress
    ) {
        Objects.requireNonNull(instance, "instance must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Instant now = clock.now();
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                heartbeatInTransaction(instance, command, remoteAddress, now)
        ), "heartbeat must return a result");
    }

    private HeartbeatResult heartbeatInTransaction(
            InstanceRequestVerifier.VerifiedInstance instance,
            HeartbeatCommand command,
            String remoteAddress,
            Instant now
    ) {
        HeartbeatInstance current = lockInstance(instance.id());
        if (!"ACTIVE".equals(current.instanceStatus())
                || !"ACTIVE".equals(current.enterpriseStatus())) {
            throw new PlatformApiException(
                    ErrorCode.ENTERPRISE_SUSPENDED,
                    "企业或部署实例已暂停"
            );
        }

        jdbcClient.sql("""
                        UPDATE pf_deployment_instance
                        SET last_heartbeat_at = :lastHeartbeatAt,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE id = :instanceId AND status = 'ACTIVE'
                        """)
                .param("lastHeartbeatAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .param("instanceId", instance.id())
                .update();

        UpsertHeartbeat heartbeat = new UpsertHeartbeat(
                instance.id(),
                command.licenseVersion(),
                "ONLINE",
                now,
                command.clientTime(),
                normalizeRemoteAddress(remoteAddress)
        );
        java.util.Optional<Long> heartbeatId = jdbcClient.sql("""
                        SELECT id FROM pf_heartbeat
                        WHERE instance_id = :instanceId
                        ORDER BY id
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("instanceId", instance.id())
                .query(Long.class)
                .optional();
        if (heartbeatId.isPresent()) {
            jdbcClient.sql("""
                            UPDATE pf_heartbeat
                            SET license_version = :licenseVersion,
                                status = :status,
                                platform_time = :platformTime,
                                client_time = :clientTime,
                                remote_address = :remoteAddress,
                                created_at = :createdAt
                            WHERE id = :heartbeatId
                            """)
                    .param("licenseVersion", heartbeat.licenseVersion())
                    .param("status", heartbeat.status())
                    .param("platformTime", Timestamp.from(heartbeat.platformTime()))
                    .param("clientTime", Timestamp.from(heartbeat.clientTime()))
                    .param("remoteAddress", heartbeat.remoteAddress())
                    .param("createdAt", Timestamp.from(now))
                    .param("heartbeatId", heartbeatId.orElseThrow())
                    .update();
        } else {
            jdbcClient.sql("""
                            INSERT INTO pf_heartbeat(
                                instance_id, license_version, status, platform_time,
                                client_time, remote_address, created_at
                            ) VALUES (
                                :instanceId, :licenseVersion, :status, :platformTime,
                                :clientTime, :remoteAddress, :createdAt
                            )
                            """)
                    .param("instanceId", heartbeat.instanceId())
                    .param("licenseVersion", heartbeat.licenseVersion())
                    .param("status", heartbeat.status())
                    .param("platformTime", Timestamp.from(heartbeat.platformTime()))
                    .param("clientTime", Timestamp.from(heartbeat.clientTime()))
                    .param("remoteAddress", heartbeat.remoteAddress())
                    .param("createdAt", Timestamp.from(now))
                    .update();
        }

        LicenseSigningService.SignedLicense latest = licenseSigningService.findLatest(instance.id());
        JsonNode licensePayload = command.licenseVersion() < latest.version()
                ? objectMapper.valueToTree(latest)
                : null;
        return new HeartbeatResult(
                instance.publicId(),
                latest.version(),
                now,
                current.instanceStatus().equals("ACTIVE") ? "ONLINE" : "SUSPENDED",
                licensePayload,
                now.plus(HEARTBEAT_INTERVAL)
        );
    }

    private HeartbeatInstance lockInstance(long instanceId) {
        return jdbcClient.sql("""
                        SELECT i.status AS instance_status, e.status AS enterprise_status
                        FROM pf_deployment_instance i
                        JOIN pf_enterprise e ON e.id = i.enterprise_id
                        WHERE i.id = :instanceId
                        FOR UPDATE
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new HeartbeatInstance(
                        rs.getString("instance_status"),
                        rs.getString("enterprise_status")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.INSTANCE_NOT_FOUND,
                        "部署实例不存在"
                ));
    }

    private static String normalizeRemoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "unknown";
        }
        return remoteAddress.length() <= 100 ? remoteAddress : remoteAddress.substring(0, 100);
    }

    public record HeartbeatCommand(long licenseVersion, Instant clientTime) {
    }

    public record HeartbeatResult(
            String instancePublicId,
            long licenseVersion,
            Instant platformTime,
            String instanceStatus,
            JsonNode licensePayload,
            Instant nextHeartbeatAt
    ) {
    }

    private record HeartbeatInstance(String instanceStatus, String enterpriseStatus) {
    }

    private record UpsertHeartbeat(
            long instanceId,
            long licenseVersion,
            String status,
            Instant platformTime,
            Instant clientTime,
            String remoteAddress
    ) {
    }
}
