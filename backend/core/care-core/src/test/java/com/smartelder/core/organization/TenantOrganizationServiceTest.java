package com.smartelder.core.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.core.api.access.DataScope;
import com.smartelder.core.api.access.TenantAccessContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantOrganizationServiceTest {

    private final InMemoryOrganizationRepository repository = new InMemoryOrganizationRepository();
    private final TenantOrganizationService service = new TenantOrganizationService(repository);
    private final TenantAccessContext manager = new TenantAccessContext(
            1L, "hq-1", "HQ", null, Set.of("HQ_ADMIN"),
            Set.of("core.store.manage", "core.store.view", "core.enterprise.manage"),
            new DataScope("ALL", Set.of()));

    @Test
    void rejectsStoreCreationWithoutManagePermission() {
        TenantAccessContext viewer = new TenantAccessContext(
                2L, "hq-2", "HQ", null, Set.of("HQ_VIEWER"), Set.of("core.store.view"),
                new DataScope("ALL", Set.of()));

        assertThatThrownBy(() -> service.createStore(
                new TenantOrganizationService.CreateStoreCommand("S001", "一号门店", null, null),
                viewer, "trace-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core.store.manage");
    }

    @Test
    void rejectsDuplicateStoreNumber() {
        service.createStore(new TenantOrganizationService.CreateStoreCommand("S001", "一号门店", null, null),
                manager, "trace-1");

        assertThatThrownBy(() -> service.createStore(
                new TenantOrganizationService.CreateStoreCommand("S001", "重复门店", null, null),
                manager, "trace-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storeNo");
    }

    @Test
    void allowsOnlyDeclaredStoreTransitionsAndClosedIsTerminal() {
        TenantOrganizationService.StoreView store = service.createStore(
                new TenantOrganizationService.CreateStoreCommand("S001", "一号门店", null, null),
                manager, "trace-1");
        service.changeStoreStatus(store.publicId(), "ACTIVE", manager, "trace-2");
        TenantOrganizationService.StoreView suspended = service.changeStoreStatus(
                store.publicId(), "SUSPENDED", manager, "trace-3");
        assertThat(suspended.status()).isEqualTo("SUSPENDED");
        assertThat(suspended.operational()).isFalse();

        TenantOrganizationService.StoreView closed = service.changeStoreStatus(
                store.publicId(), "CLOSED", manager, "trace-4");
        assertThatThrownBy(() -> service.changeStoreStatus(
                closed.publicId(), "ACTIVE", manager, "trace-5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transition");
    }

    private static final class InMemoryOrganizationRepository implements TenantOrganizationRepository {
        private final List<TenantOrganizationService.StoreView> stores = new ArrayList<>();

        @Override
        public TenantOrganizationService.EnterpriseView findEnterprise() {
            return new TenantOrganizationService.EnterpriseView("enterprise-1", "ORG-1", "康养企业",
                    null, null, null, 0);
        }

        @Override
        public TenantOrganizationService.EnterpriseView updateEnterprise(
                TenantOrganizationService.UpdateEnterpriseCommand command,
                long expectedVersion, long actorId, String traceId) {
            return new TenantOrganizationService.EnterpriseView("enterprise-1", "ORG-1", command.name(),
                    command.legalName(), command.contactName(), command.contactPhone(), expectedVersion + 1);
        }

        @Override
        public TenantOrganizationService.StoreView insertStore(
                TenantOrganizationService.CreateStoreCommand command, String publicId, long actorId, String traceId) {
            if (stores.stream().anyMatch(store -> store.storeNo().equals(command.storeNo()))) {
                throw new org.springframework.dao.DuplicateKeyException("storeNo already exists");
            }
            TenantOrganizationService.StoreView store = new TenantOrganizationService.StoreView(
                    publicId, command.storeNo(), command.name(), "DRAFT", 0, false);
            stores.add(store);
            return store;
        }

        @Override
        public TenantOrganizationService.StoreView findStore(String publicId) {
            return stores.stream().filter(store -> store.publicId().equals(publicId)).findFirst().orElse(null);
        }

        @Override
        public TenantOrganizationService.StoreView changeStoreStatus(
                String publicId, String status, long expectedVersion, long actorId, String traceId) {
            TenantOrganizationService.StoreView old = findStore(publicId);
            if (old == null) return null;
            TenantOrganizationService.StoreView changed = new TenantOrganizationService.StoreView(
                    old.publicId(), old.storeNo(), old.name(), status, old.version() + 1, "ACTIVE".equals(status));
            stores.set(stores.indexOf(old), changed);
            return changed;
        }

        @Override
        public List<TenantOrganizationService.StoreView> listStores() {
            return List.copyOf(stores);
        }

        @Override
        public List<TenantOrganizationService.StoreView> listStores(Set<Long> storeIds) {
            return stores.stream().filter(store -> storeIds.contains((long) stores.indexOf(store) + 1)).toList();
        }

        @Override
        public Long findStoreId(String publicId) {
            for (int i = 0; i < stores.size(); i++) {
                if (stores.get(i).publicId().equals(publicId)) return (long) i + 1;
            }
            return null;
        }
    }
}
