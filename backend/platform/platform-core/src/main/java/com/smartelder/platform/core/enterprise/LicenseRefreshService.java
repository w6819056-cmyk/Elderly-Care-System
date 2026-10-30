package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public class LicenseRefreshService {

    private final JdbcClient jdbcClient;
    private final LicenseSigningService licenseSigningService;
    private final PlatformClock clock;

    public LicenseRefreshService(
            JdbcClient jdbcClient,
            LicenseSigningService licenseSigningService,
            PlatformClock clock
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.licenseSigningService = Objects.requireNonNull(
                licenseSigningService,
                "licenseSigningService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public LicenseSigningService.SignedLicense current(
            InstanceRequestVerifier.VerifiedInstance instance
    ) {
        return licenseSigningService.findLatest(instance.id());
    }

    public Acknowledgement acknowledge(
            InstanceRequestVerifier.VerifiedInstance instance,
            long version
    ) {
        if (version < 1) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "version 必须大于 0");
        }
        Instant now = clock.now();
        int updated = jdbcClient.sql("""
                        UPDATE pf_license
                        SET acknowledged_at = COALESCE(acknowledged_at, :acknowledgedAt)
                        WHERE instance_id = :instanceId
                          AND version = :version
                          AND status = 'ACTIVE'
                        """)
                .param("acknowledgedAt", Timestamp.from(now))
                .param("instanceId", instance.id())
                .param("version", version)
                .update();
        Timestamp acknowledgedAt = jdbcClient.sql("""
                        SELECT acknowledged_at
                        FROM pf_license
                        WHERE instance_id = :instanceId
                          AND version = :version
                          AND status = 'ACTIVE'
                        """)
                .param("instanceId", instance.id())
                .param("version", version)
                .query(Timestamp.class)
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.LICENSE_NOT_FOUND,
                        "实例授权文件不存在"
                ));
        if (acknowledgedAt == null) {
            throw new PlatformApiException(ErrorCode.LICENSE_NOT_FOUND, "实例授权文件不存在");
        }
        if (updated > 1) {
            throw new IllegalStateException("license acknowledgment updated multiple rows");
        }
        return new Acknowledgement(version, acknowledgedAt.toInstant());
    }

    public record Acknowledgement(long version, Instant acknowledgedAt) {
    }
}
