package com.smartelder.module.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ModuleDescriptor(
        String code,
        String name,
        String version,
        int menuOrder,
        List<ModuleDependency> dependencies,
        Set<String> permissionCodes
) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

    public ModuleDescriptor {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        Objects.requireNonNull(permissionCodes, "permissionCodes must not be null");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid module code: " + code);
        }
        if (name.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("name and version must not be blank");
        }
        if (menuOrder <= 0) {
            throw new IllegalArgumentException("menuOrder must be positive");
        }
        if (dependencies.stream().anyMatch(dependency -> dependency.moduleCode().equals(code))) {
            throw new IllegalArgumentException("A module cannot depend on itself");
        }
        if (permissionCodes.stream().anyMatch(permission -> !PERMISSION.matcher(permission).matches())) {
            throw new IllegalArgumentException("Invalid permission code");
        }
        dependencies = List.copyOf(dependencies);
        permissionCodes = Set.copyOf(permissionCodes);
    }
}