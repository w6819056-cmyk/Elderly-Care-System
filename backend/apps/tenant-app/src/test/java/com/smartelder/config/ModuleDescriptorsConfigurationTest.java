package com.smartelder.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModuleDescriptorsConfigurationTest {
    private final ModuleDescriptorsConfiguration configuration = new ModuleDescriptorsConfiguration();

    @Test
    void staticMembershipDescriptorKeepsCatalogPermissionsButHasNoRuntimeBehavior() {
        var descriptor = configuration.membershipModuleDescriptor();

        assertThat(descriptor.code()).isEqualTo("membership");
        assertThat(descriptor.permissionCodes()).contains("membership:member:read", "membership:member:write");
        assertThat(descriptor.menuEntries()).isEmpty();
        assertThat(descriptor.resourceOperations()).isEmpty();
        assertThat(descriptor.windDownOperations()).isEmpty();
    }

    @Test
    void unimplementedModuleDescriptorsKeepCatalogDependenciesWithoutRuntimeBehavior() {
        var institution = configuration.institutionModuleDescriptor();
        var community = configuration.communityModuleDescriptor();

        assertThat(institution.dependencies()).extracting("moduleCode").containsExactly("membership");
        assertThat(institution.permissionCodes()).contains("institution:bed:read", "institution:bed:write");
        assertThat(community.permissionCodes()).contains("community:service:read", "community:service:write");
        assertThat(institution.menuEntries()).isEmpty();
        assertThat(institution.resourceOperations()).isEmpty();
        assertThat(institution.windDownOperations()).isEmpty();
        assertThat(community.menuEntries()).isEmpty();
        assertThat(community.resourceOperations()).isEmpty();
        assertThat(community.windDownOperations()).isEmpty();
    }
}
