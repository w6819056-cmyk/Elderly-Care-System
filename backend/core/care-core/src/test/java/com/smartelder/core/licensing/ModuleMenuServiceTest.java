package com.smartelder.core.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartelder.core.api.access.DataScope;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.module.api.ModuleDependency;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleMenuEntry;
import com.smartelder.module.api.ModuleRegistry;
import com.smartelder.module.api.ModuleResourceOperation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleMenuServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private final ModuleAccessQuery query = mock(ModuleAccessQuery.class);
    private final ModuleMenuService service = new ModuleMenuService(registry(), query, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void menuUsesRegisteredPermissionAndSharedAccessDecision() {
        when(query.evaluate(eq("membership"), eq("MemberResource"), eq("read"), eq(51L), any()))
                .thenReturn(new ModuleAccessDecision("ACTIVE", true, null, 3));
        TenantAccessContext store = new TenantAccessContext(5, "staff-5", "STORE", 51L,
                Set.of("STORE_STAFF"), Set.of("membership:member:read"), new DataScope("STORES", Set.of(51L)));
        assertThat(service.list(store, null)).extracting(ModuleMenuService.MenuView::moduleCode)
                .containsExactly("membership");
    }

    @Test
    void omittedPermissionHidesMenuAndForgedStoreIsRejected() {
        TenantAccessContext viewer = new TenantAccessContext(6, "staff-6", "HQ", null,
                Set.of("HQ_VIEWER"), Set.of(), new DataScope("ALL", Set.of()));
        assertThat(service.list(viewer, null)).isEmpty();
        TenantAccessContext store = new TenantAccessContext(7, "staff-7", "STORE", 51L,
                Set.of("STORE_STAFF"), Set.of("membership:member:read"), new DataScope("STORES", Set.of(51L)));
        assertThatThrownBy(() -> service.list(store, 52L)).isInstanceOf(TenantModuleException.class)
                .hasMessageContaining("outside the current data scope");
    }

    @Test
    void menusFollowDescriptorOrderInsteadOfRegistryMapIterationOrder() {
        ModuleRegistry orderedRegistry = new ModuleRegistry(List.of(
                new ModuleDescriptor("community", "社区", "1.0.0", 30, List.of(), Set.of("community:service:read"),
                        List.of(new ModuleMenuEntry("community", "社区", "/community", "heart", "community:service:read")),
                        Set.of(new ModuleResourceOperation("CommunityResource", "read", "community:service:read", true)), Set.of()),
                new ModuleDescriptor("membership", "会员", "1.0.0", 10, List.of(), Set.of("membership:member:read"),
                        List.of(new ModuleMenuEntry("membership", "会员", "/members", "users", "membership:member:read")),
                        Set.of(new ModuleResourceOperation("MemberResource", "read", "membership:member:read", true)), Set.of())));
        when(query.evaluate(eq("membership"), eq("MemberResource"), eq("read"), eq(51L), any()))
                .thenReturn(new ModuleAccessDecision("ACTIVE", true, null, 3));
        when(query.evaluate(eq("community"), eq("CommunityResource"), eq("read"), eq(51L), any()))
                .thenReturn(new ModuleAccessDecision("ACTIVE", true, null, 3));
        TenantAccessContext store = new TenantAccessContext(5, "staff-5", "STORE", 51L,
                Set.of("STORE_STAFF"), Set.of("membership:member:read", "community:service:read"),
                new DataScope("STORES", Set.of(51L)));
        ModuleMenuService orderedService = new ModuleMenuService(orderedRegistry, query, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(orderedService.list(store, null)).extracting(ModuleMenuService.MenuView::moduleCode)
                .containsExactly("membership", "community");
    }

    private static ModuleRegistry registry() {
        return new ModuleRegistry(List.of(new ModuleDescriptor("membership", "会员", "1.0.0", 10,
                List.of(), Set.of("membership:member:read"),
                List.of(new ModuleMenuEntry("membership", "会员管理", "/membership", "users", "membership:member:read")),
                Set.of(new ModuleResourceOperation("MemberResource", "read", "membership:member:read", true, false)), Set.of())));
    }
}
