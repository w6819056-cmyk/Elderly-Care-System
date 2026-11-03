package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
            long version,
            String sha256
    ) {
        if (version < 1) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "version 必须大于 0");
        }
        IssuedPayload issued = jdbcClient.sql("""
                        SELECT payload_json, schema_version
                        FROM pf_license
                        WHERE instance_id = :instanceId AND version = :version AND status = 'ACTIVE'
                        """)
                .param("instanceId", instance.id())
                .param("version", version)
                .query((rs, rowNum) -> new IssuedPayload(
                        rs.getString("payload_json"), rs.getInt("schema_version")))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.LICENSE_NOT_FOUND, "实例授权文件不存在"));
        String expectedDigest = LicenseCanonicalizer.sha256(issued.payloadJson());
        if (issued.schemaVersion() >= 2 && !matchesDigest(sha256, expectedDigest)) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "授权摘要与签发内容不匹配");
        }
        if (sha256 != null && !matchesDigest(sha256, expectedDigest)) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "授权摘要与签发内容不匹配");
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
        return new Acknowledgement(version, expectedDigest, acknowledgedAt.toInstant());
    }

    private static boolean matchesDigest(String supplied, String expected) {
        if (supplied == null || !supplied.matches("[0-9a-f]{64}")) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
    }

    private record IssuedPayload(String payloadJson, int schemaVersion) {
    }

    public record Acknowledgement(long version, String sha256, Instant acknowledgedAt) {
    }
}
