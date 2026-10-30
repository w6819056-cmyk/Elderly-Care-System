package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public class InstanceService {

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final EnterpriseService enterpriseService;
    private final LicenseSigningService licenseSigningService;

    public InstanceService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            EnterpriseService enterpriseService,
            LicenseSigningService licenseSigningService
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.enterpriseService = Objects.requireNonNull(
                enterpriseService,
                "enterpriseService must not be null"
        );
        this.licenseSigningService = Objects.requireNonNull(
                licenseSigningService,
                "licenseSigningService must not be null"
        );
    }

    public HeartbeatResult heartbeat(
            InstanceRequestVerifier.VerifiedInstance instance,
            HeartbeatCommand command,
            String remoteAddress
    ) {
        Instant now = clock.now();
        int updated = jdbcClient.sql("""
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
        if (updated != 1) {
            throw new PlatformApiException(ErrorCode.INSTANCE_NOT_FOUND, "部署实例不可用");
        }
        jdbcClient.sql("""
                        INSERT INTO pf_heartbeat(
                            instance_id, license_version, status, platform_time,
                            client_time, remote_address, created_at
                        ) VALUES (
                            :instanceId, :licenseVersion, 'ONLINE', :platformTime,
                            :clientTime, :remoteAddress, :createdAt
                        )
                        """)
                .param("instanceId", instance.id())
                .param("licenseVersion", command.licenseVersion())
                .param("platformTime", Timestamp.from(now))
                .param("clientTime", Timestamp.from(command.clientTime()))
                .param("remoteAddress", normalizeRemoteAddress(remoteAddress))
                .param("createdAt", Timestamp.from(now))
                .update();
        return new HeartbeatResult(
                instance.publicId(),
                command.licenseVersion(),
                now,
                "ONLINE"
        );
    }

    public List<InstanceView> listByEnterprise(String enterprisePublicId) {
        enterpriseService.findByPublicId(enterprisePublicId);
        return jdbcClient.sql("""
                        SELECT i.public_id, i.enterprise_id, e.public_id AS enterprise_public_id,
                               i.installation_id, i.tenant_database_id, i.fingerprint,
                               i.status, i.last_heartbeat_at, i.license_version,
                               i.created_at, i.updated_at
                        FROM pf_deployment_instance i
                        JOIN pf_enterprise e ON e.id = i.enterprise_id
                        WHERE e.public_id = :enterprisePublicId
                        ORDER BY i.id DESC
                        """)
                .param("enterprisePublicId", enterprisePublicId)
                .query((rs, rowNum) -> {
                    Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");
                    return new InstanceView(
                            rs.getString("public_id"),
                            rs.getString("enterprise_public_id"),
                            rs.getString("installation_id"),
                            rs.getString("tenant_database_id"),
                            rs.getString("fingerprint"),
                            rs.getString("status"),
                            heartbeat == null ? null : heartbeat.toInstant(),
                            rs.getLong("license_version"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant()
                    );
                })
                .list();
    }

    public LicenseSigningService.SignedLicense currentLicense(
            InstanceRequestVerifier.VerifiedInstance instance
    ) {
        return licenseSigningService.findLatest(instance.id());
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
            String status
    ) {
    }

    public record InstanceView(
            String publicId,
            String enterprisePublicId,
            String installationId,
            String tenantDatabaseId,
            String fingerprint,
            String status,
            Instant lastHeartbeatAt,
            long licenseVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
