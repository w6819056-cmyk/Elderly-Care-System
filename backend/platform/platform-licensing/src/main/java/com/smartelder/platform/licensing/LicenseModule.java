package com.smartelder.platform.licensing;

import java.time.Instant;

public record LicenseModule(
        String code,
        String licenseType,
        String status,
        Instant startsAt,
        Instant expiresAt,
        boolean perpetual,
        int storeQuota
) {

    public LicenseModule {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("module.code must not be blank");
        }
        if (licenseType == null || licenseType.isBlank()) {
            throw new IllegalArgumentException("module.licenseType must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("module.status must not be blank");
        }
        if (startsAt == null) {
            throw new IllegalArgumentException("module.startsAt must not be null");
        }
        if (storeQuota < 0) {
            throw new IllegalArgumentException("module.storeQuota must not be negative");
        }
    }
}
