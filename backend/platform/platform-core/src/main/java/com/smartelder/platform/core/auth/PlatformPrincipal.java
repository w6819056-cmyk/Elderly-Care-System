package com.smartelder.platform.core.auth;

import java.util.Objects;
import java.util.Set;

public record PlatformPrincipal(
        long userId,
        String publicId,
        String username,
        Set<String> roleCodes,
        Set<String> permissionCodes
) {

    public PlatformPrincipal {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("publicId must not be blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        roleCodes = Set.copyOf(Objects.requireNonNull(roleCodes, "roleCodes must not be null"));
        permissionCodes = Set.copyOf(
                Objects.requireNonNull(permissionCodes, "permissionCodes must not be null")
        );
    }
}
