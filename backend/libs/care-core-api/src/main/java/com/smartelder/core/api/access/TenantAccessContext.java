package com.smartelder.core.api.access;

import java.util.Set;

public record TenantAccessContext(
        long staffId,
        String staffPublicId,
        String staffType,
        Long homeStoreId,
        Set<String> roleCodes,
        Set<String> permissionCodes,
        DataScope dataScope) {

    public TenantAccessContext {
        if (staffId <= 0) {
            throw new IllegalArgumentException("staffId must be positive");
        }
        if (staffPublicId == null || staffPublicId.isBlank()) {
            throw new IllegalArgumentException("staffPublicId is required");
        }
        if (!"HQ".equals(staffType) && !"STORE".equals(staffType)) {
            throw new IllegalArgumentException("staffType must be HQ or STORE");
        }
        roleCodes = immutableNonBlankSet(roleCodes, "roleCodes");
        permissionCodes = immutableNonBlankSet(permissionCodes, "permissionCodes");
        if (dataScope == null) {
            throw new IllegalArgumentException("dataScope is required");
        }
        if ("STORE".equals(staffType)) {
            if (homeStoreId == null || homeStoreId <= 0) {
                throw new IllegalArgumentException("STORE staff requires exactly one homeStoreId");
            }
            if (!"STORES".equals(dataScope.type()) || !dataScope.storeIds().equals(Set.of(homeStoreId))) {
                throw new IllegalArgumentException("STORE staff scope must be limited to homeStoreId");
            }
        } else if (homeStoreId != null) {
            throw new IllegalArgumentException("HQ staff cannot have a homeStoreId");
        }
    }

    private static Set<String> immutableNonBlankSet(Set<String> values, String field) {
        Set<String> copy = Set.copyOf(values == null ? Set.of() : values);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " cannot contain blank values");
        }
        return copy;
    }
}
