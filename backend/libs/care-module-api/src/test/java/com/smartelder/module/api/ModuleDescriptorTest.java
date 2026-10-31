package com.smartelder.module.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleDescriptorTest {

    @Test
    void createsImmutableDescriptor() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                20,
                List.of(new ModuleDependency("membership", "1.0.0")),
                Set.of("institution:bed:read", "institution:bed:write")
        );

        assertThat(descriptor.code()).isEqualTo("institution");
        assertThat(descriptor.menuOrder()).isEqualTo(20);
        assertThat(descriptor.permissionCodes()).contains("institution:bed:read");
        assertThatThrownBy(() -> descriptor.dependencies().add(
                new ModuleDependency("community", "1.0.0")
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidCodeAndMenuOrder() {
        assertThatThrownBy(() -> new ModuleDescriptor(
                "Institution Module",
                "机构康养",
                "1.0.0",
                20,
                List.of(),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");

        assertThatThrownBy(() -> new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                0,
                List.of(),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("menuOrder");
    }

    @Test
    void rejectsSelfDependencyAndInvalidPermissionCode() {
        assertThatThrownBy(() -> new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                20,
                List.of(new ModuleDependency("institution", "1.0.0")),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self");

        assertThatThrownBy(() -> new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                20,
                List.of(),
                Set.of("bad permission")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void descriptorCarriesImmutableMenusOperationsAndWindDownCommands() {
        var descriptor = new ModuleDescriptor("institution", "机构康养", "1.0.0", 20,
                List.of(new ModuleDependency("membership", "1.0.0")),
                Set.of("institution:bed:read"),
                List.of(new ModuleMenuEntry("institution", "机构康养", "/institution", "bed", "institution:bed:read")),
                Set.of(new ModuleResourceOperation("AdmissionController", "read", "institution:bed:read", true, false)),
                Set.of("AdmissionController#complete_existing_admission"));
        assertThat(descriptor.menuEntries()).hasSize(1);
        assertThat(descriptor.resourceOperations()).hasSize(1);
        assertThat(descriptor.windDownOperations()).containsExactly("AdmissionController#complete_existing_admission");
        assertThatThrownBy(() -> descriptor.menuEntries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> descriptor.resourceOperations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void oldDescriptorConstructorRemainsSourceCompatible() {
        var descriptor = new ModuleDescriptor("institution", "机构康养", "1.0.0", 20, List.of(), Set.of());
        assertThat(descriptor.menuEntries()).isEmpty();
        assertThat(descriptor.resourceOperations()).isEmpty();
        assertThat(descriptor.windDownOperations()).isEmpty();
    }
}
