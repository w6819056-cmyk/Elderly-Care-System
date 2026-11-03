package com.smartelder.core.licensing;

import java.util.List;
import java.util.Set;

public interface TenantModuleRepository {
    List<TenantLicenseRepository.ModuleGrant> entitlements();
    Set<String> enabledModules();
    List<ModuleSummary> listModules();
    List<AssignmentView> listAssignments(String moduleCode);
    List<StoreRef> listActiveStores();
    StoreRef findStore(String publicId);
    AssignmentView assign(String moduleCode, String storePublicId, String idempotencyKey, long actorId, String traceId);
    void activation(String moduleCode, boolean enabled, long actorId, String traceId);
    void revoke(String moduleCode, String storePublicId, long actorId, String traceId);

    record StoreRef(long id, String publicId, String storeNo, String name, String status) {}
    record ModuleSummary(String moduleCode, String licenseType, String licenseStatus,
                         java.time.Instant startsAt, java.time.Instant expiresAt, boolean perpetual,
                         long licenseVersion, int storeQuota, int assignedStores, boolean enabled,
                         String effectiveState) {}
    record AssignmentView(String publicId, String moduleCode, String storePublicId, String storeName,
                          String status, String idempotencyKey) {}
}
