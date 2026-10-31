package com.smartelder.core.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.core.api.access.DataScope;
import com.smartelder.core.api.access.TenantAccessContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantModuleServiceTest {
    private final FakeRepository repository = new FakeRepository();
    private final TenantModuleService service = new TenantModuleService(repository);
    private final TenantAccessContext hq = new TenantAccessContext(7, "hq-7", "HQ", null,
            Set.of("HQ_ADMIN"), Set.of("core.module.view", "core.module.manage",
                    "core.module.assignment.manage"), new DataScope("ALL", Set.of()));

    @Test
    void assignmentRequiresEnabledFormalEntitlementAndActiveStoreAndUsesIndependentQuota() {
        repository.entitlements = List.of(
                new TenantLicenseRepository.ModuleGrant("membership", "FORMAL", "ACTIVE", null, null, true, 1),
                new TenantLicenseRepository.ModuleGrant("institution", "FORMAL", "ACTIVE", null, null, true, 2));
        repository.enabled.addAll(Set.of("membership", "institution"));
        repository.stores.put("store-a", new TenantModuleRepository.StoreRef(1, "store-a", "门店A", "ACTIVE"));
        repository.stores.put("store-b", new TenantModuleRepository.StoreRef(2, "store-b", "门店B", "ACTIVE"));

        var first = service.assignStore("membership", "store-a", "idem-1", hq, "trace-1");
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.assignStore("membership", "store-b", "idem-2", hq, "trace-2"))
                .isInstanceOf(TenantModuleException.class).hasMessageContaining("quota");
        assertThat(service.assignStore("institution", "store-b", "idem-3", hq, "trace-3").storePublicId())
                .isEqualTo("store-b");
    }

    @Test
    void storeSuspensionDoesNotReleaseQuotaAndExplicitHqRevocationDoes() {
        repository.entitlements = List.of(
                new TenantLicenseRepository.ModuleGrant("membership", "FORMAL", "ACTIVE", null, null, true, 1));
        repository.enabled.add("membership");
        repository.stores.put("store-a", new TenantModuleRepository.StoreRef(1, "store-a", "门店A", "ACTIVE"));
        repository.stores.put("store-b", new TenantModuleRepository.StoreRef(2, "store-b", "门店B", "ACTIVE"));
        service.assignStore("membership", "store-a", "idem-1", hq, "trace-1");
        repository.stores.put("store-a", new TenantModuleRepository.StoreRef(1, "store-a", "门店A", "CLOSED"));

        assertThatThrownBy(() -> service.assignStore("membership", "store-b", "idem-2", hq, "trace-2"))
                .isInstanceOf(TenantModuleException.class).hasMessageContaining("quota");
        service.revokeAssignment("membership", "store-a", hq, "trace-3");
        assertThat(service.assignStore("membership", "store-b", "idem-4", hq, "trace-4").storePublicId())
                .isEqualTo("store-b");
    }

    @Test
    void trialEntitlementCannotSatisfyFormalDependency() {
        repository.entitlements = List.of(
                new TenantLicenseRepository.ModuleGrant("membership", "TRIAL", "ACTIVE", null, null, true, 5),
                new TenantLicenseRepository.ModuleGrant("institution", "FORMAL", "ACTIVE", null, null, true, 5));
        repository.dependencies.put("institution", Set.of("membership"));
        assertThatThrownBy(() -> service.setActivation("institution", true, hq, "trace-1"))
                .isInstanceOf(TenantModuleException.class).hasMessageContaining("dependency");
    }

    @Test
    void requiresHqPermissions() {
        TenantAccessContext viewer = new TenantAccessContext(8, "hq-8", "HQ", null,
                Set.of("HQ_VIEWER"), Set.of(), new DataScope("ALL", Set.of()));
        assertThatThrownBy(() -> service.listModules(viewer)).isInstanceOf(TenantModuleException.class);
    }

    private static final class FakeRepository implements TenantModuleRepository {
        List<TenantLicenseRepository.ModuleGrant> entitlements = List.of();
        final java.util.Map<String, TenantModuleRepository.StoreRef> stores = new java.util.HashMap<>();
        final java.util.Set<String> enabled = new java.util.HashSet<>();
        final java.util.Map<String, Set<String>> dependencies = new java.util.HashMap<>();
        final java.util.Map<String, TenantModuleRepository.AssignmentView> assignments = new java.util.HashMap<>();
        @Override public List<TenantLicenseRepository.ModuleGrant> entitlements() { return entitlements; }
        @Override public Set<String> enabledModules() { return Set.copyOf(enabled); }
        @Override public Set<String> dependencies(String module) { return dependencies.getOrDefault(module, Set.of()); }
        @Override public List<TenantModuleRepository.ModuleSummary> listModules() { return List.of(); }
        @Override public List<TenantModuleRepository.AssignmentView> listAssignments(String module) { return List.copyOf(assignments.values()); }
        @Override public TenantModuleRepository.StoreRef findStore(String publicId) { return stores.get(publicId); }
        @Override public TenantModuleRepository.AssignmentView assign(String module, String store, String key, long actor, String trace) {
            String id = module + ":" + store;
            if (assignments.containsKey(id)) return assignments.get(id);
            if (assignments.values().stream().filter(a -> a.moduleCode().equals(module) && a.status().equals("ACTIVE")).count()
                    >= entitlements.stream().filter(e -> e.moduleCode().equals(module)).findFirst().orElseThrow().storeQuota())
                throw new TenantModuleException("MODULE_QUOTA_EXCEEDED", "module quota exceeded");
            var value = new TenantModuleRepository.AssignmentView(id, module, store, stores.get(store).name(), "ACTIVE", key);
            assignments.put(id, value); return value;
        }
        @Override public void activation(String module, boolean enable, long actor, String trace) { if (enable) enabled.add(module); else enabled.remove(module); }
        @Override public void revoke(String module, String store, long actor, String trace) {
            String id = module + ":" + store;
            var current = assignments.get(id);
            if (current != null) assignments.put(id, new TenantModuleRepository.AssignmentView(id, module, store,
                    current.storeName(), "REVOKED", current.idempotencyKey()));
        }
    }
}
