package com.smartelder.platform.core.enterprise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import com.smartelder.platform.licensing.LicenseSigner;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

public class LicenseSigningService {

    private static final int SCHEMA_VERSION = 1;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final LicenseSigner licenseSigner;
    private final PlatformClock clock;

    public LicenseSigningService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            LicenseSigner licenseSigner,
            PlatformClock clock
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("enterpriseId", enterprisePublicId);
        payload.put("instanceId", instancePublicId);
        payload.put("installationId", installationId);
        payload.put("tenantDatabaseId", tenantDatabaseId);
        payload.put("fingerprint", fingerprint);
        payload.put("licenseVersion", 1);
        payload.put("status", "ACTIVE");
        payload.put("issuedAt", now.toString());
        payload.put("modules", java.util.List.of());
        payload.put("notBefore", now.toString());
        payload.put("expiresAt", null);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new PlatformApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "生成授权内容失败",
                    exception
            );
        }
        String signature;
        try {
            signature = licenseSigner.sign(payloadJson);
        } catch (PlatformApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PlatformApiException(
                    ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "授权签名服务不可用",
                    exception
            );
        }
        String publicId = PublicIds.newId("lic");
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
                .param("signature", signature)
                .param("issuedAt", Timestamp.from(now))
                .update();
        return new SignedLicense(
                publicId,
                1,
                SCHEMA_VERSION,
                payloadJson,
                signature,
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
