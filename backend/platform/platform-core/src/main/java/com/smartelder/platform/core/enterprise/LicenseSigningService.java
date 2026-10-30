package com.smartelder.platform.core.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import com.smartelder.platform.licensing.LicenseDocument;
import com.smartelder.platform.licensing.LicenseSignature;
import com.smartelder.platform.licensing.LicenseSigner;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public class LicenseSigningService {

    private static final int SCHEMA_VERSION = 1;
    private static final int OFFLINE_GRACE_DAYS = 7;

    private final JdbcClient jdbcClient;
    private final LicenseSigner licenseSigner;
    private final PlatformClock clock;

    public LicenseSigningService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            LicenseSigner licenseSigner,
            PlatformClock clock
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.licenseSigner = Objects.requireNonNull(licenseSigner, "licenseSigner must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SignedLicense issueForActivation(
            long enterpriseId,
            String enterprisePublicId,
            long instanceId,
            String instancePublicId,
            String installationId,
            String tenantDatabaseId,
            String fingerprint
    ) {
        Instant now = clock.now();
        String orgNo = jdbcClient.sql("SELECT org_no FROM pf_enterprise WHERE id = :id")
                .param("id", enterpriseId)
                .query(String.class)
                .single();
        String publicId = PublicIds.newId("lic");
        LicenseDocument unsigned = new LicenseDocument(
                SCHEMA_VERSION,
                publicId,
                orgNo,
                instancePublicId,
                fingerprint,
                now,
                now,
                null,
                OFFLINE_GRACE_DAYS,
                new LicenseDocument.HeartbeatPolicy(6, 30),
                List.of(),
                new LicenseSignature(licenseSigner.algorithm(), licenseSigner.keyId(), null)
        );
        LicenseDocument signed;
        try {
            signed = licenseSigner.sign(unsigned);
        } catch (RuntimeException exception) {
            throw new PlatformApiException(
                    ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "授权签名服务不可用",
                    exception
            );
        }
        String payloadJson = LicenseCanonicalizer.canonicalize(unsigned);
        jdbcClient.sql("""
                        INSERT INTO pf_license(
                            public_id, enterprise_id, instance_id, version, schema_version,
                            payload_json, signature, status, issued_at
                        ) VALUES (
                            :publicId, :enterpriseId, :instanceId, 1, :schemaVersion,
                            :payloadJson, :signature, 'ACTIVE', :issuedAt
                        )
                        """)
                .param("publicId", publicId)
                .param("enterpriseId", enterpriseId)
                .param("instanceId", instanceId)
                .param("schemaVersion", SCHEMA_VERSION)
                .param("payloadJson", payloadJson)
                .param("signature", signed.signature().value())
                .param("issuedAt", Timestamp.from(now))
                .update();
        jdbcClient.sql("""
                        UPDATE pf_deployment_instance
                        SET license_version = 1, updated_at = :updatedAt, version = version + 1
                        WHERE id = :instanceId
                        """)
                .param("updatedAt", Timestamp.from(now))
                .param("instanceId", instanceId)
                .update();
        return new SignedLicense(
                publicId,
                1,
                SCHEMA_VERSION,
                payloadJson,
                signed.signature().value(),
                licenseSigner.algorithm(),
                licenseSigner.keyId(),
                now
        );
    }

    public SignedLicense findLatest(long instanceId) {
        return jdbcClient.sql("""
                        SELECT public_id, version, schema_version, payload_json, signature, issued_at
                        FROM pf_license
                        WHERE instance_id = :instanceId AND status = 'ACTIVE'
                        ORDER BY version DESC
                        LIMIT 1
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new SignedLicense(
                        rs.getString("public_id"),
                        rs.getLong("version"),
                        rs.getInt("schema_version"),
                        rs.getString("payload_json"),
                        rs.getString("signature"),
                        licenseSigner.algorithm(),
                        licenseSigner.keyId(),
                        rs.getTimestamp("issued_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.LICENSE_NOT_FOUND,
                        "实例授权文件不存在"
                ));
    }

    public record SignedLicense(
            String publicId,
            long version,
            int schemaVersion,
            String payloadJson,
            String signature,
            String signatureAlgorithm,
            String keyId,
            Instant issuedAt
    ) {
    }
}