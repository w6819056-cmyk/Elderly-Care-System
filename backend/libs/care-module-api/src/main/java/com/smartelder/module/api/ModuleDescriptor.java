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
        Set<String> permissionCodes,
        List<ModuleMenuEntry> menuEntries,
        Set<ModuleResourceOperation> resourceOperations,
        Set<String> windDownOperations
) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

    public ModuleDescriptor {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        Objects.requireNonNull(permissionCodes, "permissionCodes must not be null");
        Objects.requireNonNull(menuEntries, "menuEntries must not be null");
        Objects.requireNonNull(resourceOperations, "resourceOperations must not be null");
        Objects.requireNonNull(windDownOperations, "windDownOperations must not be null");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid module code: " + code);
        }
        if (name.isBlank() || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("name must not be blank and version must be numeric dotted form");
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
        for (ModuleResourceOperation operation : resourceOperations) {
            if (!permissionCodes.contains(operation.permissionCode()))
                throw new IllegalArgumentException("Resource operation permission is not declared by module");
        }
        for (ModuleMenuEntry entry : menuEntries) {
            if (!permissionCodes.contains(entry.permissionCode()))
                throw new IllegalArgumentException("Menu permission is not declared by module");
        }
        if (windDownOperations.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z][A-Za-z0-9_.]*#[a-z][a-z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("Wind-down operations must use resource#operation keys");
        }
        dependencies = List.copyOf(dependencies);
        permissionCodes = Set.copyOf(permissionCodes);
        menuEntries = List.copyOf(menuEntries);
        resourceOperations = Set.copyOf(resourceOperations);
        windDownOperations = Set.copyOf(windDownOperations);
    }

    public ModuleDescriptor(String code, String name, String version, int menuOrder,
            List<ModuleDependency> dependencies, Set<String> permissionCodes) {
        this(code, name, version, menuOrder, dependencies, permissionCodes, List.of(), Set.of(), Set.of());
    }
}
