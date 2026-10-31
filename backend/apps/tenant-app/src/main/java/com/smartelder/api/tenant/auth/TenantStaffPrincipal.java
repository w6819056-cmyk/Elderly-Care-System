package com.smartelder.api.tenant.auth;

public record TenantStaffPrincipal(
        long staffId,
        String staffPublicId,
        String username,
        String staffType,
        Long homeStoreId) {
}
