package com.smartelder.core.api.iam;

import java.util.regex.Pattern;

public record TenantPermission(String code, String moduleCode, String name) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*(?:[.:_-][a-z0-9]+)*");

    public TenantPermission {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code must be a registered permission code");
        }
        if (moduleCode == null || moduleCode.isBlank()) {
            throw new IllegalArgumentException("moduleCode is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
