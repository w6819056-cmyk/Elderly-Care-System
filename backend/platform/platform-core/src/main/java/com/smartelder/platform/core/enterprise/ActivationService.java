package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import com.smartelder.platform.licensing.Ed25519LicenseVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ActivationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CODE_BYTES = 24;

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final PlatformAuditService auditService;
    private final EnterpriseService enterpriseService;
    private final LicenseSigningService licenseSigningService;
    private final TransactionTemplate transactionTemplate;

    public ActivationService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            EnterpriseService enterpriseService,
            LicenseSigningService licenseSigningService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.enterpriseService = Objects.requireNonNull(
                enterpriseService,
                "enterpriseService must not be null"
        );
        this.licenseSigningService = Objects.requireNonNull(
                licenseSigningService,
                "licenseSigningService must not be null"
        );
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public ActivationCodeResult createActivationCode(
            String enterprisePublicId,
            CreateActivationCodeCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        validateCodePolicy(command);
        EnterpriseService.EnterpriseView enterprise = enterpriseService.findByPublicId(
                enterprisePublicId
        );
        if (EnterpriseStatus.CLOSED.name().equals(enterprise.status())) {
            throw new PlatformApiException(
                    ErrorCode.ENTERPRISE_STATE_CONFLICT,
                    "已关闭企业不能生成激活码"
            );
        }

        Instant now = clock.now();
        Instant expiresAt = now.plus(Duration.ofHours(command.ttlHours()));
        String plaintext = newActivationCode();
        String codeHash = sha256Hex(plaintext);
        String publicId = PublicIds.newId("act");
        jdbcClient.sql("""
                        INSERT INTO pf_activation_code(
                            public_id, enterprise_id, code_hash, expires_at, used_at,
                            used_instance_id, attempt_count, max_attempts, created_by, created_at
                        ) VALUES (
                            :publicId,
                            (SELECT id FROM pf_enterprise WHERE public_id = :enterprisePublicId),
                            :codeHash, :expiresAt, NULL, NULL, 0, :maxAttempts,
                            :createdBy, :createdAt
                        )
                        """)
                .param("publicId", publicId)
                .param("enterprisePublicId", enterprisePublicId)
                .param("codeHash", codeHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("maxAttempts", command.maxAttempts())
                .param("createdBy", actor.userId())
                .param("createdAt", Timestamp.from(now))
                .update();

        auditService.record(
                actor.userId(),
                actor.publicId(),
                "PLATFORM_ACTIVATION_CODE_CREATED",
                "ACTIVATION_CODE",
                publicId,
                "{\"enterprisePublicId\":\"" + enterprisePublicId
                        + "\",\"maxAttempts\":" + command.maxAttempts() + "}",
                traceId
        );
        return new ActivationCodeResult(
                publicId,
                plaintext,
                expiresAt,
                command.maxAttempts()
        );
    }

    public ActivatedInstance activate(ActivationCommand command, String traceId) {
        String codeHash = sha256Hex(command.activationCode().trim());
        ActivationCodeRow knownCode = findCodeByHash(codeHash)
                .orElseThrow(ActivationService::invalidCode);
        try {
            return transactionTemplate.execute(status -> activateInTransaction(
                    command,
                    codeHash,
                    traceId
            ));
        } catch (PlatformApiException exception) {
            if (exception.code() == ErrorCode.ACTIVATION_CODE_INVALID) {
                recordFailedAttempt(knownCode.id());
            }
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            throw new PlatformApiException(
                    ErrorCode.INSTANCE_IDENTITY_CONFLICT,
                    "部署实例身份已存在",
                    exception
            );
        }
    }

    private ActivatedInstance activateInTransaction(
            ActivationCommand command,
            String codeHash,
            String traceId
    ) {
        Instant now = clock.now();
        ActivationCodeRow code = findCodeForUpdate(codeHash)
                .orElseThrow(ActivationService::invalidCode);
        if (code.usedAt() != null) {
            throw new PlatformApiException(ErrorCode.ACTIVATION_CODE_USED, "激活码已被使用");
        }
        if (!code.expiresAt().isAfter(now)) {
            throw invalidCode();
        }
        if (code.attemptCount() >= code.maxAttempts()) {
            throw invalidCode();
        }
        EnterpriseStatus enterpriseStatus = enterpriseService.requireStatus(code.enterpriseId());
        if (enterpriseStatus != EnterpriseStatus.ACTIVE) {
            throw new PlatformApiException(
                    ErrorCode.ENTERPRISE_SUSPENDED,
                    "企业已暂停或关闭，不能激活实例"
            );
        }
        String fingerprint = validatedFingerprint(
                command.publicKeyPem(),
                command.fingerprint()
        );
        Long duplicateCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM pf_deployment_instance
                        WHERE enterprise_id = :enterpriseId
                          AND installation_id = :installationId
                          AND tenant_database_id = :tenantDatabaseId
                        """)
                .param("enterpriseId", code.enterpriseId())
                .param("installationId", command.installationId())
                .param("tenantDatabaseId", command.tenantDatabaseId())
                .query(Long.class)
                .single();
        if (duplicateCount > 0) {
            throw new PlatformApiException(
                    ErrorCode.INSTANCE_IDENTITY_CONFLICT,
                    "部署实例身份已存在"
            );
        }

        String instancePublicId = PublicIds.newId("ins");
        jdbcClient.sql("""
                        INSERT INTO pf_deployment_instance(
                            public_id, enterprise_id, installation_id, tenant_database_id,
                            public_key_pem, fingerprint, status, last_heartbeat_at,
                            license_version, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :enterpriseId, :installationId, :tenantDatabaseId,
                            :publicKeyPem, :fingerprint, 'ACTIVE', NULL,
                            1, :createdAt, :updatedAt, 0
                        )
                        """)
                .param("publicId", instancePublicId)
                .param("enterpriseId", code.enterpriseId())
                .param("installationId", command.installationId())
                .param("tenantDatabaseId", command.tenantDatabaseId())
                .param("publicKeyPem", command.publicKeyPem())
                .param("fingerprint", fingerprint)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        long instanceId = jdbcClient.sql("""
                        SELECT id FROM pf_deployment_instance WHERE public_id = :publicId
                        """)
                .param("publicId", instancePublicId)
                .query(Long.class)
                .single();
        int claimed = jdbcClient.sql("""
                        UPDATE pf_activation_code
                        SET used_at = :usedAt, used_instance_id = :instanceId
                        WHERE id = :id AND used_at IS NULL
                        """)
                .param("usedAt", Timestamp.from(now))
                .param("instanceId", instanceId)
                .param("id", code.id())
                .update();
        if (claimed != 1) {
            throw new PlatformApiException(ErrorCode.ACTIVATION_CODE_USED, "激活码已被使用");
        }

        String enterprisePublicId = jdbcClient.sql("""
                        SELECT public_id FROM pf_enterprise WHERE id = :id
                        """)
                .param("id", code.enterpriseId())
                .query(String.class)
                .single();
        LicenseSigningService.SignedLicense license = licenseSigningService.issueForActivation(
                code.enterpriseId(),
                enterprisePublicId,
                instanceId,
                instancePublicId,
                command.installationId(),
                command.tenantDatabaseId(),
                fingerprint
        );
        auditService.record(
                null,
                null,
                "INSTANCE_ACTIVATED",
                "DEPLOYMENT_INSTANCE",
                instancePublicId,
                "{\"enterprisePublicId\":\"" + enterprisePublicId
                        + "\",\"licenseVersion\":1}",
                traceId
        );
        auditService.record(
                null,
                "SYSTEM",
                "LICENSE_ISSUED",
                "DEPLOYMENT_INSTANCE",
                instancePublicId,
                "{\"licenseId\":\"" + license.publicId()
                        + "\",\"version\":" + license.version() + "}",
                traceId
        );
        return new ActivatedInstance(
                instancePublicId,
                enterprisePublicId,
                command.installationId(),
                command.tenantDatabaseId(),
                fingerprint,
                "ACTIVE",
                1,
                license
        );
    }

    private void recordFailedAttempt(long activationCodeId) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        UPDATE pf_activation_code
                        SET attempt_count = attempt_count + 1
                        WHERE id = :id
                          AND used_at IS NULL
                          AND expires_at > :now
                          AND attempt_count < max_attempts
                        """)
                .param("id", activationCodeId)
                .param("now", Timestamp.from(clock.now()))
                .update());
    }

    private Optional<ActivationCodeRow> findCodeByHash(String codeHash) {
        return jdbcClient.sql("""
                        SELECT id, enterprise_id, expires_at, used_at,
                               attempt_count, max_attempts
                        FROM pf_activation_code
                        WHERE code_hash = :codeHash
                        """)
                .param("codeHash", codeHash)
                .query((rs, rowNum) -> mapCodeRow(rs))
                .optional();
    }

    private Optional<ActivationCodeRow> findCodeForUpdate(String codeHash) {
        return jdbcClient.sql("""
                        SELECT id, enterprise_id, expires_at, used_at,
                               attempt_count, max_attempts
                        FROM pf_activation_code
                        WHERE code_hash = :codeHash
                        FOR UPDATE
                        """)
                .param("codeHash", codeHash)
                .query((rs, rowNum) -> mapCodeRow(rs))
                .optional();
    }

    private static ActivationCodeRow mapCodeRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp usedAt = rs.getTimestamp("used_at");
        return new ActivationCodeRow(
                rs.getLong("id"),
                rs.getLong("enterprise_id"),
                rs.getTimestamp("expires_at").toInstant(),
                usedAt == null ? null : usedAt.toInstant(),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts")
        );
    }

    private static String validatedFingerprint(String publicKeyPem, String claimedFingerprint) {
        try {
            PublicKey publicKey = Ed25519LicenseVerifier.publicKeyFromPem(publicKeyPem);
            String expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded())
            );
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    claimedFingerprint.trim().toLowerCase(java.util.Locale.ROOT)
                            .getBytes(StandardCharsets.UTF_8)
            )) {
                throw invalidCode();
            }
            return expected;
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCode();
        }
    }

    private static String newActivationCode() {
        byte[] random = new byte[CODE_BYTES];
        SECURE_RANDOM.nextBytes(random);
        return "ACT-" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static PlatformApiException invalidCode() {
        return new PlatformApiException(
                ErrorCode.ACTIVATION_CODE_INVALID,
                "激活码无效、已过期或尝试次数已用尽"
        );
    }

    private static void validateCodePolicy(CreateActivationCodeCommand command) {
        if (command.ttlHours() < 1 || command.ttlHours() > 720) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "ttlHours 必须介于 1 和 720 之间");
        }
        if (command.maxAttempts() < 1 || command.maxAttempts() > 20) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "maxAttempts 必须介于 1 和 20 之间"
            );
        }
    }

    private record ActivationCodeRow(
            long id,
            long enterpriseId,
            Instant expiresAt,
            Instant usedAt,
            int attemptCount,
            int maxAttempts
    ) {
    }

    public record CreateActivationCodeCommand(int ttlHours, int maxAttempts) {
    }

    public record ActivationCodeResult(
            String publicId,
            String activationCode,
            Instant expiresAt,
            int maxAttempts
    ) {
    }

    public record ActivationCommand(
            String activationCode,
            String installationId,
            String tenantDatabaseId,
            String publicKeyPem,
            String fingerprint
    ) {
    }

    public record ActivatedInstance(
            String instancePublicId,
            String enterprisePublicId,
            String installationId,
            String tenantDatabaseId,
            String fingerprint,
            String status,
            long licenseVersion,
            LicenseSigningService.SignedLicense license
    ) {
    }
}
