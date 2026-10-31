package com.smartelder.core.api.access;

import java.util.Set;

public record DataScope(String type, Set<Long> storeIds) {

    private static final Set<String> TYPES = Set.of("ALL", "STORE_GROUP", "STORES", "SELF");

    public DataScope {
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be ALL, STORE_GROUP, STORES, or SELF");
        }
        storeIds = Set.copyOf(storeIds == null ? Set.of() : storeIds);
        if (storeIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("storeIds must contain positive IDs");
        }
        if ("STORES".equals(type) && storeIds.isEmpty()) {
            throw new IllegalArgumentException("STORES scope requires storeIds");
        }
        if (("ALL".equals(type) || "SELF".equals(type)) && !storeIds.isEmpty()) {
            throw new IllegalArgumentException(type + " scope cannot contain storeIds");
        }
    }
}
