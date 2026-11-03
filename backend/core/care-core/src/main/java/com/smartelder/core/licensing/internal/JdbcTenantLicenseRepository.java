package com.smartelder.core.licensing.internal;

import com.smartelder.core.licensing.LicenseSnapshotConflictException;
import com.smartelder.core.licensing.TenantLicenseRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcTenantLicenseRepository implements TenantLicenseRepository {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public JdbcTenantLicenseRepository(
            JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public void accept(VerifiedLicenseSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        transactions.executeWithoutResult(status -> acceptInTransaction(snapshot));
    }

    private void acceptInTransaction(VerifiedLicenseSnapshot snapshot) {
        String enterpriseOrgNo = jdbc.sql("SELECT org_no FROM core_enterprise WHERE id = 1")
                .query(String.class).optional()
                .orElseThrow(() -> new LicenseSnapshotConflictException("tenant enterprise is not initialized"));
        if (!enterpriseOrgNo.equals(snapshot.orgNo())) {
            throw new LicenseSnapshotConflictException("license belongs to another enterprise");
        }

        StoredIdentity current = jdbc.sql("""
                        SELECT org_no, deployment_instance_id, instance_fingerprint, license_version, sha256
                        FROM lic_license_snapshot WHERE id = 1 FOR UPDATE
                        """)
                .query((rs, row) -> new StoredIdentity(
                        rs.getString("org_no"), rs.getString("deployment_instance_id"),
                        rs.getString("instance_fingerprint"), rs.getLong("license_version"),
                        rs.getString("sha256")))
                .optional().orElse(null);
        Instant now = clock.instant();

        if (current != null) {
            if (!current.orgNo().equals(snapshot.orgNo())
                    || !current.deploymentInstanceId().equals(snapshot.deploymentInstanceId())
                    || !current.instanceFingerprint().equals(snapshot.instanceFingerprint())) {
                throw new LicenseSnapshotConflictException("license instance identity changed");
            }
            if (snapshot.version() < current.version()) {
                throw new LicenseSnapshotConflictException("license version rollback rejected");
            }
            if (snapshot.version() == current.version()) {
                if (!current.sha256().equals(snapshot.sha256())) {
                    throw new LicenseSnapshotConflictException("license digest changed at the same version");
                }
                jdbc.sql("""
                                UPDATE lic_license_snapshot
                                SET last_platform_time = :platformTime, last_successful_sync_at = :syncedAt,
                                    platform_instance_status = :instanceStatus, sync_status = 'CURRENT',
                                    version = version + 1
                                WHERE id = 1
                                """)
                        .param("platformTime", ts(snapshot.platformTime()))
                        .param("syncedAt", ts(now))
                        .param("instanceStatus", snapshot.instanceStatus()).update();
                recordAttempt(snapshot.version(), "ACCEPTED_REPLAY", now);
                return;
            }
            jdbc.sql("DELETE FROM lic_module_entitlement").update();
            updateSnapshot(snapshot, now);
        } else {
            insertSnapshot(snapshot, now);
        }

        for (ModuleGrant module : snapshot.modules()) {
            jdbc.sql("""
                            INSERT INTO lic_module_entitlement(
                                module_code, license_type, status, starts_at, expires_at, perpetual,
                                store_quota, license_version, updated_at
                            ) VALUES (
                                :moduleCode, :licenseType, :status, :startsAt, :expiresAt, :perpetual,
                                :storeQuota, :licenseVersion, :updatedAt
                            )
                            """)
                    .param("moduleCode", module.moduleCode())
                    .param("licenseType", module.licenseType())
                    .param("status", module.status())
                    .param("startsAt", ts(module.startsAt()))
                    .param("expiresAt", module.expiresAt() == null ? null : ts(module.expiresAt()))
                    .param("perpetual", module.perpetual())
                    .param("storeQuota", module.storeQuota())
                    .param("licenseVersion", snapshot.version())
                    .param("updatedAt", ts(now))
                    .update();
        }
        recordAttempt(snapshot.version(), "ACCEPTED", now);
    }

    private void insertSnapshot(VerifiedLicenseSnapshot value, Instant now) {
        jdbc.sql("""
                        INSERT INTO lic_license_snapshot(
                            id, org_no, deployment_instance_id, instance_fingerprint, license_id,
                            schema_version, license_version, raw_license_json, canonical_payload, sha256,
                            signature_algorithm, key_id, signature_value, issued_at, not_before,
                            license_expires_at, offline_grace_days, heartbeat_interval_hours,
                            last_platform_time, last_successful_sync_at, platform_instance_status,
                            sync_status, version
                        ) VALUES (
                            1, :orgNo, :instanceId, :fingerprint, :licenseId, :schemaVersion, :licenseVersion,
                            :rawJson, :canonicalPayload, :sha256, :algorithm, :keyId, :signature,
                            :issuedAt, :notBefore, :expiresAt, :offlineGraceDays, :heartbeatHours,
                            :platformTime, :syncedAt, :instanceStatus, 'CURRENT', 0
                        )
                        """)
                .param("orgNo", value.orgNo()).param("instanceId", value.deploymentInstanceId())
                .param("fingerprint", value.instanceFingerprint()).param("licenseId", value.licenseId())
                .param("schemaVersion", value.schemaVersion()).param("licenseVersion", value.version())
                .param("rawJson", value.rawLicenseJson()).param("canonicalPayload", value.canonicalPayload())
                .param("sha256", value.sha256()).param("algorithm", value.signatureAlgorithm())
                .param("keyId", value.keyId()).param("signature", value.signatureValue())
                .param("issuedAt", ts(value.issuedAt())).param("notBefore", ts(value.notBefore()))
                .param("expiresAt", value.expiresAt() == null ? null : ts(value.expiresAt()))
                .param("offlineGraceDays", value.offlineGraceDays())
                .param("heartbeatHours", value.heartbeatIntervalHours())
                .param("platformTime", ts(value.platformTime())).param("syncedAt", ts(now))
                .param("instanceStatus", value.instanceStatus()).update();
    }

    private void updateSnapshot(VerifiedLicenseSnapshot value, Instant now) {
        jdbc.sql("""
                        UPDATE lic_license_snapshot SET
                            license_id = :licenseId, schema_version = :schemaVersion,
                            license_version = :licenseVersion, raw_license_json = :rawJson,
                            canonical_payload = :canonicalPayload, sha256 = :sha256,
                            signature_algorithm = :algorithm, key_id = :keyId,
                            signature_value = :signature, issued_at = :issuedAt, not_before = :notBefore,
                            license_expires_at = :expiresAt, offline_grace_days = :offlineGraceDays,
                            heartbeat_interval_hours = :heartbeatHours, last_platform_time = :platformTime,
                            last_successful_sync_at = :syncedAt, platform_instance_status = :instanceStatus,
                            sync_status = 'CURRENT', version = version + 1 WHERE id = 1
                        """)
                .param("licenseId", value.licenseId()).param("schemaVersion", value.schemaVersion())
                .param("licenseVersion", value.version()).param("rawJson", value.rawLicenseJson())
                .param("canonicalPayload", value.canonicalPayload()).param("sha256", value.sha256())
                .param("algorithm", value.signatureAlgorithm()).param("keyId", value.keyId())
                .param("signature", value.signatureValue()).param("issuedAt", ts(value.issuedAt()))
                .param("notBefore", ts(value.notBefore()))
                .param("expiresAt", value.expiresAt() == null ? null : ts(value.expiresAt()))
                .param("offlineGraceDays", value.offlineGraceDays())
                .param("heartbeatHours", value.heartbeatIntervalHours())
                .param("platformTime", ts(value.platformTime())).param("syncedAt", ts(now))
                .param("instanceStatus", value.instanceStatus()).update();
    }

    @Override
    public LicenseStatus currentStatus() {
        return jdbc.sql("""
                        SELECT org_no, deployment_instance_id, instance_fingerprint, license_id,
                               schema_version, license_version, sha256, last_platform_time,
                               last_successful_sync_at, platform_instance_status
                        FROM lic_license_snapshot WHERE id = 1
                        """)
                .query((rs, row) -> new LicenseStatus(
                        rs.getString("org_no"), rs.getString("deployment_instance_id"),
                        rs.getString("instance_fingerprint"), rs.getString("license_id"),
                        rs.getInt("schema_version"), rs.getLong("license_version"), rs.getString("sha256"),
                        rs.getTimestamp("last_platform_time").toInstant(),
                        rs.getTimestamp("last_successful_sync_at").toInstant(),
                        rs.getString("platform_instance_status"), listModules()))
                .optional().orElse(null);
    }

    @Override
    public void markPlatformStatus(String status) {
        if (!"SUSPENDED".equals(status) && !"REVOKED".equals(status)) {
            throw new IllegalArgumentException("unsupported platform instance status");
        }
        jdbc.sql("""
                        UPDATE lic_license_snapshot
                        SET platform_instance_status = :status, sync_status = 'CONTROL_SUSPENDED',
                            version = version + 1
                        WHERE id = 1
                        """)
                .param("status", status).update();
    }

    private List<ModuleGrant> listModules() {
        return jdbc.sql("""
                        SELECT module_code, license_type, status, starts_at, expires_at,
                               perpetual, store_quota
                        FROM lic_module_entitlement ORDER BY module_code
                        """)
                .query((rs, row) -> new ModuleGrant(
                        rs.getString("module_code"), rs.getString("license_type"), rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("perpetual"), rs.getInt("store_quota"))).list();
    }

    private void recordAttempt(long version, String result, Instant now) {
        jdbc.sql("INSERT INTO lic_sync_attempt (license_version, result_code, trace_id, attempted_at) "
                        + "VALUES (:version, :result, 'sync-internal', :now)")
                .param("version", version).param("result", result).param("now", ts(now)).update();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private record StoredIdentity(String orgNo, String deploymentInstanceId, String instanceFingerprint,
                                  long version, String sha256) {
    }
}
