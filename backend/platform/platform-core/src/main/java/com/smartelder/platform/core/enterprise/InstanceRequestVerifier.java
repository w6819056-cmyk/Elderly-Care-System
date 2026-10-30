package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.licensing.Ed25519LicenseVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.sql.Timestamp;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

public class InstanceRequestVerifier {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration NONCE_RETENTION = Duration.ofMinutes(10);

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;

    public InstanceRequestVerifier(JdbcClient jdbcClient, PlatformClock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public VerifiedInstance verify(VerificationInput input) {
        if (isBlank(input.instancePublicId())
                || isBlank(input.timestamp())
                || isBlank(input.nonce())
                || isBlank(input.signature())) {
            throw invalidSignature();
        }
        InstanceIdentity identity = findInstance(input.instancePublicId())
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.INSTANCE_NOT_FOUND,
                        "部署实例不存在"
                ));
        if (!"ACTIVE".equals(identity.instanceStatus())) {
            throw invalidSignature();
        }
        if (!EnterpriseStatus.ACTIVE.name().equals(identity.enterpriseStatus())) {
            throw new PlatformApiException(
                    ErrorCode.ENTERPRISE_SUSPENDED,
                    "企业已暂停或关闭"
            );
        }

        Instant requestTime;
        try {
            requestTime = Instant.parse(input.timestamp());
        } catch (DateTimeParseException exception) {
            throw invalidSignature();
        }
        Instant platformNow = clock.now();
        Duration skew = Duration.between(requestTime, platformNow).abs();
        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw invalidSignature();
        }

        String canonical = input.method() + "\n"
                + input.path() + "\n"
                + input.timestamp() + "\n"
                + input.nonce() + "\n"
                + sha256Hex(input.body());
        try {
            PublicKey publicKey = Ed25519LicenseVerifier.publicKeyFromPem(identity.publicKeyPem());
            if (!Ed25519LicenseVerifier.verifyBytes(
                    canonical.getBytes(StandardCharsets.UTF_8),
                    input.signature(),
                    publicKey
            )) {
                throw invalidSignature();
            }
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidSignature();
        }
        rememberNonce(identity.id(), input.nonce(), platformNow);
        return new VerifiedInstance(
                identity.id(),
                identity.publicId(),
                identity.enterpriseId(),
                identity.enterprisePublicId(),
                identity.installationId(),
                identity.tenantDatabaseId(),
                identity.licenseVersion()
        );
    }

    private java.util.Optional<InstanceIdentity> findInstance(String publicId) {
        return jdbcClient.sql("""
                        SELECT i.id, i.public_id, i.enterprise_id, e.public_id AS enterprise_public_id,
                               i.installation_id, i.tenant_database_id, i.public_key_pem,
                               i.status AS instance_status, i.license_version,
                               e.status AS enterprise_status
                        FROM pf_deployment_instance i
                        JOIN pf_enterprise e ON e.id = i.enterprise_id
                        WHERE i.public_id = :publicId
                        """)
                .param("publicId", publicId)
                .query((rs, rowNum) -> new InstanceIdentity(
                        rs.getLong("id"),
                        rs.getString("public_id"),
                        rs.getLong("enterprise_id"),
                        rs.getString("enterprise_public_id"),
                        rs.getString("installation_id"),
                        rs.getString("tenant_database_id"),
                        rs.getString("public_key_pem"),
                        rs.getString("instance_status"),
                        rs.getString("enterprise_status"),
                        rs.getLong("license_version")
                ))
                .optional();
    }

    private void rememberNonce(long instanceId, String nonce, Instant now) {
        jdbcClient.sql("DELETE FROM pf_instance_nonce WHERE expires_at < :now")
                .param("now", Timestamp.from(now))
                .update();
        try {
            jdbcClient.sql("""
                            INSERT INTO pf_instance_nonce(
                                instance_id, nonce, expires_at, created_at
                            ) VALUES (:instanceId, :nonce, :expiresAt, :createdAt)
                            """)
                    .param("instanceId", instanceId)
                    .param("nonce", nonce)
                    .param("expiresAt", Timestamp.from(now.plus(NONCE_RETENTION)))
                    .param("createdAt", Timestamp.from(now))
                    .update();
        } catch (DataIntegrityViolationException exception) {
            throw invalidSignature();
        }
    }
    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static PlatformApiException invalidSignature() {
        return new PlatformApiException(
                ErrorCode.INSTANCE_SIGNATURE_INVALID,
                "实例签名无效"
        );
    }

    private record InstanceIdentity(
            long id,
            String publicId,
            long enterpriseId,
            String enterprisePublicId,
            String installationId,
            String tenantDatabaseId,
            String publicKeyPem,
            String instanceStatus,
            String enterpriseStatus,
            long licenseVersion
    ) {
    }

    public record VerificationInput(
            String instancePublicId,
            String method,
            String path,
            String timestamp,
            String nonce,
            String signature,
            byte[] body
    ) {
        public VerificationInput {
            body = body == null ? new byte[0] : body.clone();
        }
    }

    public record VerifiedInstance(
            long id,
            String publicId,
            long enterpriseId,
            String enterprisePublicId,
            String installationId,
            String tenantDatabaseId,
            long licenseVersion
    ) {
    }
}
