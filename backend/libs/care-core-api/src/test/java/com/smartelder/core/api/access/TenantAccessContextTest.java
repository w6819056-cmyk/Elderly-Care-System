package com.smartelder.core.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.core.api.iam.TenantPermission;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantAccessContextTest {

    @Test
    void defensivelyCopiesResolvedSets() {
        Set<Long> storeIds = new HashSet<>(Set.of(10L));
        Set<String> roles = new HashSet<>(Set.of("HQ_OWNER"));
        Set<String> permissions = new HashSet<>(Set.of("core.store.view"));
        DataScope scope = new DataScope("STORES", storeIds);
        TenantAccessContext context = new TenantAccessContext(
                1L, "staff-1", "HQ", null, roles, permissions, scope);

        storeIds.add(20L);
        roles.add("STORE_STAFF");
        permissions.clear();

        assertThat(scope.storeIds()).containsExactly(10L);
        assertThat(context.roleCodes()).containsExactly("HQ_OWNER");
        assertThat(context.permissionCodes()).containsExactly("core.store.view");
        assertThatThrownBy(() -> context.roleCodes().add("MUTATE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsOnlyRegisteredDataScopeTypes() {
        assertThatThrownBy(() -> new DataScope("ALL_STORES", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void storeStaffMustHaveExactlyOneHomeStore() {
        assertThatThrownBy(() -> new TenantAccessContext(
                2L, "staff-2", "STORE", null, Set.of(), Set.of(),
                new DataScope("SELF", Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("homeStoreId");
    }

    @Test
    void permissionCodeCannotBeBlankOrMalformed() {
        assertThatThrownBy(() -> new TenantPermission(" ", "core", "查看门店"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }
}
