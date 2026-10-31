package com.smartelder.module.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {
    @Test
    void rejectsDuplicateModuleCodesAndUnknownDependencies() {
        var module = new ModuleDescriptor("membership", "会员", "1.0.0", 10, List.of(), Set.of());
        assertThatThrownBy(() -> new ModuleRegistry(List.of(module, module)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate module code");
        var dependent = new ModuleDescriptor("institution", "机构", "1.0.0", 20,
                List.of(new ModuleDependency("membership", "1.0.0")), Set.of());
        assertThatThrownBy(() -> new ModuleRegistry(List.of(dependent)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown module dependency");
    }

    @Test
    void rejectsDuplicateRoutesResourcesAndPermissionOwnership() {
        var first = new ModuleDescriptor("membership", "会员", "1.0.0", 10, List.of(), Set.of("membership:member:read"),
                List.of(new ModuleMenuEntry("membership", "会员", "/members", "users", "membership:member:read")),
                Set.of(new ModuleResourceOperation("MemberResource", "read", "membership:member:read", true)), Set.of());
        var route = new ModuleDescriptor("institution", "机构", "1.0.0", 20, List.of(), Set.of("institution:bed:read"),
                List.of(new ModuleMenuEntry("institution", "机构", "/members", "building", "institution:bed:read")), Set.of(), Set.of());
        assertThatThrownBy(() -> new ModuleRegistry(List.of(first, route)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate module menu route");
    }

    @Test
    void resourceOperationsAreScopedByOwningModule() {
        var membership = new ModuleDescriptor("membership", "会员", "1.0.0", 10, List.of(),
                Set.of("membership:member:read"), List.of(),
                Set.of(new ModuleResourceOperation("CustomerResource", "read", "membership:member:read", true)), Set.of());
        var community = new ModuleDescriptor("community", "社区", "1.0.0", 20, List.of(),
                Set.of("community:customer:read"), List.of(),
                Set.of(new ModuleResourceOperation("CustomerResource", "read", "community:customer:read", true)), Set.of());

        new ModuleRegistry(List.of(membership, community));
    }

    @Test
    void rejectsDependencyInstalledBelowItsDeclaredMinimumVersion() {
        var membership = new ModuleDescriptor("membership", "会员", "1.0.9", 10, List.of(), Set.of());
        var institution = new ModuleDescriptor("institution", "机构", "1.0.0", 20,
                List.of(new ModuleDependency("membership", "1.0.10")), Set.of());

        assertThatThrownBy(() -> new ModuleRegistry(List.of(membership, institution)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version is too low");
    }

    @Test
    void dependencyMinimumVersionMustBeNumericDottedForm() {
        assertThatThrownBy(() -> new ModuleDependency("membership", "latest"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numeric dotted form");
    }

    @Test
    void comparesLargeNumericVersionComponentsWithoutOverflow() {
        var membership = new ModuleDescriptor("membership", "会员", "2147483648.0", 10, List.of(), Set.of());
        var institution = new ModuleDescriptor("institution", "机构", "1.0.0", 20,
                List.of(new ModuleDependency("membership", "2147483647.9")), Set.of());

        new ModuleRegistry(List.of(membership, institution));
    }
}
