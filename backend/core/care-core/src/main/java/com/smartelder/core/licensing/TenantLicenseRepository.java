package com.smartelder.core.licensing;

import java.time.Instant;
import java.util.List;

public interface TenantLicenseRepository {

    void accept(VerifiedLicenseSnapshot snapshot);

    LicenseStatus currentStatus();

    record VerifiedLicenseSnapshot(
            String orgNo,
            String deploymentInstanceId,
            String instanceFingerprint,
            String licenseId,
            int schemaVersion,
            long version,
            String rawLicenseJson,
            String canonicalPayload,
            String sha256,
            String signatureAlgorithm,
            String keyId,
            String signatureValue,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            int offlineGraceDays,
            int heartbeatIntervalHours,
            Instant platformTime,
            String instanceStatus,
            List<ModuleGrant> modules
    ) {
        public VerifiedLicenseSnapshot {
            modules = List.copyOf(modules);
            if (schemaVersion < 2 || version < 1 || offlineGraceDays < 0 || heartbeatIntervalHours < 1) {
                throw new IllegalArgumentException("invalid verified license metadata");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid license digest");
            }
        }
    }

    record ModuleGrant(
            String moduleCode,
            String licenseType,
            String status,
            Instant startsAt,
            Instant expiresAt,
            boolean perpetual,
            int storeQuota
    ) {
    }

    record LicenseStatus(
            String orgNo,
            String deploymentInstanceId,
            String instanceFingerprint,
            String licenseId,
            int schemaVersion,
            long version,
            String sha256,
            Instant platformTime,
            Instant lastSuccessfulSyncAt,
            String instanceStatus,
            List<ModuleGrant> modules
    ) {
    }
}
