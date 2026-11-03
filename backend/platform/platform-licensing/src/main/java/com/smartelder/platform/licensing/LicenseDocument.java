package com.smartelder.platform.licensing;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record LicenseDocument(
        int schemaVersion,
        long version,
        String licenseId,
        String orgNo,
        String deploymentInstanceId,
        String instanceFingerprint,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        int offlineGraceDays,
        HeartbeatPolicy heartbeat,
        List<LicenseModule> modules,
        LicenseSignature signature
) {

    public LicenseDocument {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (schemaVersion >= 2 && version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        requireText(licenseId, "licenseId");
        requireText(orgNo, "orgNo");
        requireText(deploymentInstanceId, "deploymentInstanceId");
        requireText(instanceFingerprint, "instanceFingerprint");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(notBefore, "notBefore must not be null");
        if (offlineGraceDays < 0) {
            throw new IllegalArgumentException("offlineGraceDays must not be negative");
        }
        Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        modules = List.copyOf(Objects.requireNonNull(modules, "modules must not be null"));
        if (new HashSet<>(modules.stream().map(LicenseModule::code).toList()).size() != modules.size()) {
            throw new IllegalArgumentException("duplicate module code");
        }
    }

    public LicenseDocument withSignature(LicenseSignature newSignature) {
        return new LicenseDocument(
                schemaVersion,
                version,
                licenseId,
                orgNo,
                deploymentInstanceId,
                instanceFingerprint,
                issuedAt,
                notBefore,
                expiresAt,
                offlineGraceDays,
                heartbeat,
                modules,
                newSignature
        );
    }

    public LicenseDocument withOrgNo(String newOrgNo) {
        return new LicenseDocument(
                schemaVersion,
                version,
                licenseId,
                newOrgNo,
                deploymentInstanceId,
                instanceFingerprint,
                issuedAt,
                notBefore,
                expiresAt,
                offlineGraceDays,
                heartbeat,
                modules,
                signature
        );
    }

    public LicenseDocument withInstanceFingerprint(String newFingerprint) {
        return new LicenseDocument(
                schemaVersion,
                version,
                licenseId,
                orgNo,
                deploymentInstanceId,
                newFingerprint,
                issuedAt,
                notBefore,
                expiresAt,
                offlineGraceDays,
                heartbeat,
                modules,
                signature
        );
    }

    public LicenseDocument withModules(List<LicenseModule> newModules) {
        return new LicenseDocument(
                schemaVersion,
                version,
                licenseId,
                orgNo,
                deploymentInstanceId,
                instanceFingerprint,
                issuedAt,
                notBefore,
                expiresAt,
                offlineGraceDays,
                heartbeat,
                newModules,
                signature
        );
    }

    public LicenseDocument withVersion(long newVersion) {
        return new LicenseDocument(
                schemaVersion,
                newVersion,
                licenseId,
                orgNo,
                deploymentInstanceId,
                instanceFingerprint,
                issuedAt,
                notBefore,
                expiresAt,
                offlineGraceDays,
                heartbeat,
                modules,
                signature
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public record HeartbeatPolicy(int intervalHours, int jitterMinutes) {

        public HeartbeatPolicy {
            if (intervalHours < 1) {
                throw new IllegalArgumentException("heartbeat.intervalHours must be positive");
            }
            if (jitterMinutes < 0) {
                throw new IllegalArgumentException("heartbeat.jitterMinutes must not be negative");
            }
        }
    }
}
