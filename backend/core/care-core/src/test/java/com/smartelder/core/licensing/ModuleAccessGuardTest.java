package com.smartelder.core.licensing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartelder.core.api.access.DataScope;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleRegistry;
import com.smartelder.module.api.ModuleMenuEntry;
import com.smartelder.module.api.ModuleResourceOperation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleAccessGuardTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    private final ModuleAccessQuery query = mock(ModuleAccessQuery.class);
    private final ModuleRegistry registry = new ModuleRegistry(List.of(new ModuleDescriptor("membership", "会员", "1.0.0", 10,
            List.of(), Set.of("membership:member:read"),
            List.of(new ModuleMenuEntry("membership", "会员", "/membership", "users", "membership:member:read")),
            Set.of(new ModuleResourceOperation("MemberResource", "read", "membership:member:read", true, false)), Set.of())));
    private final ModuleAccessGuard guard = new ModuleAccessGuard(query, registry, CLOCK);

    @Test
    void rejectsStoreStaffTryingToSpoofAnotherStore() {
        TenantAccessContext storeStaff = new TenantAccessContext(1, "staff", "STORE", 11L, Set.of("STORE_STAFF"),
                Set.of("membership:member:read"), new DataScope("STORES", Set.of(11L)));
        assertThatThrownBy(() -> guard.require("membership", "MemberResource", "read", storeStaff, 22L))
                .isInstanceOf(TenantModuleException.class).hasMessageContaining("outside the current data scope");
    }

    @Test
    void requiresMappedEmployeePermissionBeforeCheckingEntitlement() {
        TenantAccessContext viewer = new TenantAccessContext(2, "staff-2", "HQ", null, Set.of("HQ_VIEWER"),
                Set.of(), new DataScope("ALL", Set.of()));
        assertThatThrownBy(() -> guard.require("membership", "MemberResource", "read", viewer, null))
                .isInstanceOf(TenantModuleException.class).hasMessageContaining("missing permission");
    }

    @Test
    void usesResourceAndOperationTogetherToResolvePermission() {
        ModuleRegistry registryWithSameOperationNames = new ModuleRegistry(List.of(new ModuleDescriptor(
                "membership", "会员", "1.0.0", 10, List.of(),
                Set.of("membership:member:read", "membership:profile:read"), List.of(),
                Set.of(new ModuleResourceOperation("MemberResource", "read", "membership:member:read", true),
                        new ModuleResourceOperation("ProfileResource", "read", "membership:profile:read", true)), Set.of())));
        ModuleAccessGuard resourceAwareGuard = new ModuleAccessGuard(query, registryWithSameOperationNames, CLOCK);
        TenantAccessContext profileReader = new TenantAccessContext(2, "staff-2", "HQ", null, Set.of("HQ_VIEWER"),
                Set.of("membership:profile:read"), new DataScope("ALL", Set.of()));

        assertThatThrownBy(() -> resourceAwareGuard.require("membership", "MemberResource", "read", profileReader, null))
                .isInstanceOf(TenantModuleException.class).hasMessageContaining("missing permission");
    }
}
