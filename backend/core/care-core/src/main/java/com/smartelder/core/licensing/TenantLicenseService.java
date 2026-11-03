package com.smartelder.core.licensing;

import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class TenantLicenseService {

    private final TenantLicenseRepository repository;

    public TenantLicenseService(TenantLicenseRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public void acceptVerifiedSnapshot(TenantLicenseRepository.VerifiedLicenseSnapshot snapshot) {
        repository.accept(snapshot);
    }

    public TenantLicenseRepository.LicenseStatus currentStatus() {
        return repository.currentStatus();
    }

    public void markPlatformStatus(String status) {
        repository.markPlatformStatus(status);
    }
}
