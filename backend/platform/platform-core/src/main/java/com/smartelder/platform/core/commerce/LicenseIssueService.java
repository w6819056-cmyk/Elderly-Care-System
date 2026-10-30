package com.smartelder.platform.core.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import com.smartelder.platform.licensing.LicenseDocument;
import com.smartelder.platform.licensing.LicenseModule;
import com.smartelder.platform.licensing.LicenseSignature;
import com.smartelder.platform.licensing.LicenseSigner;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class LicenseIssueService {

    private static final int SCHEMA_VERSION = 1;
    private static final int OFFLINE_GRACE_DAYS = 7;
    private static final LicenseDocument.HeartbeatPolicy HEARTBEAT_POLICY =
            new LicenseDocument.HeartbeatPolicy(6, 30);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final LicenseSigner licenseSigner;
    private final PlatformClock clock;
    private final TransactionTemplate transactionTemplate;

    public LicenseIssueService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            LicenseSigner licenseSigner,
            PlatformClock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.licenseSigner = Objects.requireNonNull(licenseSigner, "licenseSigner must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public IssuedLicense issueForInstance(long instanceId, String traceId) {
        Objects.requireNonNull(traceId, "traceId must not be null");
        return Objects.requireNonNull(
                transactionTemplate.execute(status -> issueInTransaction(instanceId)),
                "license issuance must return a result"
        );
    }

    private IssuedLicense issueInTransaction(long instanceId) {
        InstanceRow instance = lockInstance(instanceId);
        EnterpriseRow enterprise = findEnterprise(instance.enterpriseId());
        Instant now = clock.now();
        List<LicenseModule> modules = loadModules(instance, enterprise, now);
        long nextVersion = instance.licenseVersion() + 1;
        String licenseId = PublicIds.newId("lic");

        LicenseDocument unsigned = new LicenseDocument(
                SCHEMA_VERSION,
                licenseId,
                enterprise.orgNo(),
                instance.publicId(),
                instance.fingerprint(),
                now,
                now,
                null,
                OFFLINE_GRACE_DAYS,
                HEARTBEAT_POLICY,
                modules,
                new LicenseSignature(licenseSigner.algorithm(), licenseSigner.keyId(), null)
        );
        LicenseDocument signed = licenseSigner.sign(unsigned);
        String payloadJson = LicenseCanonicalizer.canonicalize(unsigned);

        jdbcClient.sql("""
                        INSERT INTO pf_license(
                            public_id, enterprise_id, instance_id, version, schema_version,
                            payload_json, signature, status, issued_at
                        ) VALUES (
                            :publicId, :enterpriseId, :instanceId, :version, :schemaVersion,
                            :payloadJson, :signature, 'ACTIVE', :issuedAt
                        )
                        """)
                .param("publicId", licenseId)
                .param("enterpriseId", instance.enterpriseId())
                .param("instanceId", instance.id())
                .param("version", nextVersion)
                .param("schemaVersion", SCHEMA_VERSION)
                .param("payloadJson", payloadJson)
                .param("signature", signed.signature().value())
                .param("issuedAt", Timestamp.from(now))
                .update();

        int updated = jdbcClient.sql("""
                        UPDATE pf_deployment_instance
                        SET license_version = :version, updated_at = :updatedAt, version = version + 1
                        WHERE id = :instanceId AND license_version = :currentVersion
                        """)
                .param("version", nextVersion)
                .param("updatedAt", Timestamp.from(now))
                .param("instanceId", instance.id())
                .param("currentVersion", instance.licenseVersion())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("部署实例授权版本已变化");
        }

        writeRefreshRequested(instance.publicId(), nextVersion);

        return new IssuedLicense(
                nextVersion,
                payloadJson,
                signed.signature().value(),
                licenseId
        );
    }

    public void requestRefreshForInstance(long instanceId, String traceId) {
        Objects.requireNonNull(traceId, "traceId must not be null");
        transactionTemplate.executeWithoutResult(status -> {
            InstanceRow instance = lockInstance(instanceId);
            writeRefreshRequested(instance.publicId(), instance.licenseVersion());
        });
    }

    private void writeRefreshRequested(String instancePublicId, long licenseVersion) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "instancePublicId", instancePublicId,
                    "licenseVersion", licenseVersion
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize license refresh event", exception);
        }
        jdbcClient.sql("""
                        INSERT INTO pf_outbox_event(
                            public_id, aggregate_type, aggregate_id, event_type,
                            payload_json, status, created_at, published_at
                        )
                        SELECT :publicId, 'DEPLOYMENT_INSTANCE', :instancePublicId,
                               'license.refresh.requested', :payload, 'PENDING', :createdAt, NULL
                        WHERE NOT EXISTS (
                            SELECT 1 FROM pf_outbox_event
                            WHERE aggregate_type = 'DEPLOYMENT_INSTANCE'
                              AND aggregate_id = :instancePublicId
                              AND event_type = 'license.refresh.requested'
                              AND status = 'PENDING'
                        )
                        """)
                .param("publicId", PublicIds.newId("evt"))
                .param("instancePublicId", instancePublicId)
                .param("payload", payload)
                .param("createdAt", Timestamp.from(clock.now()))
                .update();
    }

    private InstanceRow lockInstance(long instanceId) {
        return jdbcClient.sql("""
                        SELECT id, enterprise_id, public_id, fingerprint, status, license_version
                        FROM pf_deployment_instance
                        WHERE id = :instanceId
                        FOR UPDATE
                        """)
                .param("instanceId", instanceId)
                .query(this::mapInstance)
                .optional()
                .orElseThrow(() -> new IllegalStateException("部署实例不存在: " + instanceId));
    }

    private EnterpriseRow findEnterprise(long enterpriseId) {
        return jdbcClient.sql("SELECT org_no, status FROM pf_enterprise WHERE id = :enterpriseId")
                .param("enterpriseId", enterpriseId)
                .query((rs, rowNum) -> new EnterpriseRow(rs.getString("org_no"), rs.getString("status")))
                .single();
    }

    private List<LicenseModule> loadModules(
            InstanceRow instance,
            EnterpriseRow enterprise,
            Instant now
    ) {
        return jdbcClient.sql("""
                        SELECT e.module_id, m.code, e.license_type, e.status, e.starts_at,
                               e.expires_at, e.perpetual, e.store_quota
                        FROM pf_entitlement e
                        JOIN pf_module_definition m ON m.id = e.module_id
                        WHERE e.enterprise_id = :enterpriseId
                        ORDER BY m.menu_order, m.id
                        """)
                .param("enterpriseId", instance.enterpriseId())
                .query((rs, rowNum) -> mapLicenseModule(rs, instance, enterprise, now))
                .list();
    }

    private static LicenseModule mapLicenseModule(
            ResultSet rs,
            InstanceRow instance,
            EnterpriseRow enterprise,
            Instant now
    ) throws SQLException {
        Instant startsAt = rs.getTimestamp("starts_at").toInstant();
        Timestamp expiresTimestamp = rs.getTimestamp("expires_at");
        Instant expiresAt = expiresTimestamp == null ? null : expiresTimestamp.toInstant();
        boolean perpetual = rs.getBoolean("perpetual");
        String licenseType = rs.getString("license_type");
        String entitlementStatus = rs.getString("status");
        String status;
        if (!"ACTIVE".equals(instance.status())
                || !"ACTIVE".equals(enterprise.status())
                || "SUSPENDED".equals(entitlementStatus)) {
            status = "SUSPENDED";
        } else {
            status = EntitlementService.deriveStatus(
                    new EntitlementService.Entitlement(
                            instance.enterpriseId(),
                            instance.id(),
                            rs.getLong("module_id"),
                            licenseType,
                            entitlementStatus,
                            startsAt,
                            expiresAt,
                            perpetual,
                            rs.getInt("store_quota"),
                            false
                    ),
                    now
            );
        }
        return new LicenseModule(
                rs.getString("code"),
                licenseType,
                status,
                startsAt,
                expiresAt,
                perpetual,
                rs.getInt("store_quota")
        );
    }

    private InstanceRow mapInstance(ResultSet rs, int rowNum) throws SQLException {
        return new InstanceRow(
                rs.getLong("id"),
                rs.getLong("enterprise_id"),
                rs.getString("public_id"),
                rs.getString("fingerprint"),
                rs.getString("status"),
                rs.getLong("license_version")
        );
    }

    public record IssuedLicense(
            long version,
            String payloadJson,
            String signature,
            String licenseId
    ) {
    }

    private record InstanceRow(
            long id,
            long enterpriseId,
            String publicId,
            String fingerprint,
            String status,
            long licenseVersion
    ) {
    }

    private record EnterpriseRow(String orgNo, String status) {
    }
}
