package com.smartelder.api.tenant.auth;

import com.smartelder.core.api.access.TenantAccessContext;

public record TenantStaffPrincipal(
        long staffId,
        String staffPublicId,
        String username,
        String staffType,
        Long homeStoreId,
        TenantAccessContext accessContext) {
}
