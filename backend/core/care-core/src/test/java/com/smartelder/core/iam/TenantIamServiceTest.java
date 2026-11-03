package com.smartelder.core.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantIamServiceTest {

    @Test
    void unionsPermissionGroupsAndLetsDenyOverrideAllow() {
        Set<String> permissions = TenantIamService.resolvePermissions(
                Set.of(Set.of("core.store.view", "iam.staff.view"), Set.of("core.store.manage")),
                Map.of("core.store.manage", "DENY", "iam.role.view", "ALLOW"),
                Set.of("core.store.view", "core.store.manage", "iam.staff.view", "iam.role.view"));

        assertThat(permissions).containsExactlyInAnyOrder(
                "core.store.view", "iam.staff.view", "iam.role.view");
    }

    @Test
    void rejectsUnregisteredPermissionsAndInvalidEffect() {
        assertThatThrownBy(() -> TenantIamService.resolvePermissions(
                Set.of(Set.of("custom.injected.permission")), Map.of(), Set.of("core.store.view")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unregistered");
        assertThatThrownBy(() -> TenantIamService.resolvePermissions(
                Set.of(), Map.of("core.store.view", "MAYBE"), Set.of("core.store.view")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effect");
    }

    @Test
    void rejectsRoleAssignmentAcrossStaffDomains() {
        assertThatThrownBy(() -> TenantIamService.requireRoleDomain("STORE", "HQ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
        TenantIamService.requireRoleDomain("STORE", "STORE");
    }

    @Test
    void preventsChangingProtectedOwnerRole() {
        assertThatThrownBy(() -> TenantIamService.requireRoleMutable(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected");
        TenantIamService.requireRoleMutable(false);
    }
}
