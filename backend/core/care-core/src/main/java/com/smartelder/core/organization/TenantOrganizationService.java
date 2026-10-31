package com.smartelder.core.organization;

import com.smartelder.core.api.access.TenantAccessContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantOrganizationService {

    private static final Map<String, Set<String>> STORE_TRANSITIONS = Map.of(
            "DRAFT", Set.of("ACTIVE"),
            "ACTIVE", Set.of("SUSPENDED", "CLOSED"),
            "SUSPENDED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of());

    private final TenantOrganizationRepository repository;

    public TenantOrganizationService(TenantOrganizationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public EnterpriseView getEnterprise(TenantAccessContext actor) {
        requirePermission(actor, "core.enterprise.view");
        EnterpriseView view = repository.findEnterprise();
        if (view == null) throw new OrganizationException("NOT_FOUND", "enterprise profile not initialized");
        return view;
    }

    @Transactional
    public EnterpriseView updateEnterprise(
            UpdateEnterpriseCommand command, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "core.enterprise.manage");
        validateRequired(command.name(), "name");
        EnterpriseView current = repository.findEnterprise();
        if (current == null) throw new OrganizationException("NOT_FOUND", "enterprise profile not initialized");
        EnterpriseView updated = repository.updateEnterprise(command, current.version(), actor.staffId(), traceId);
        if (updated == null) throw new OrganizationException("VERSION_CONFLICT", "enterprise profile changed concurrently");
        return updated;
    }

    @Transactional(readOnly = true)
    public List<StoreView> listStores(TenantAccessContext actor) {
        requirePermission(actor, "core.store.view");
        return repository.listStores();
    }

    @Transactional
    public StoreView createStore(CreateStoreCommand command, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "core.store.manage");
        validateRequired(command.storeNo(), "storeNo");
        validateRequired(command.name(), "name");
        try {
            return repository.insertStore(command, UUID.randomUUID().toString(), actor.staffId(), traceId);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new OrganizationException("BUSINESS_RULE_VIOLATION", "storeNo or publicId already exists");
        }
    }

    @Transactional
    public StoreView changeStoreStatus(
            String publicId, String targetStatus, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "core.store.manage");
        StoreView current = repository.findStore(publicId);
        if (current == null) throw new OrganizationException("NOT_FOUND", "store not found");
        if (!STORE_TRANSITIONS.getOrDefault(current.status(), Set.of()).contains(targetStatus)) {
            throw new OrganizationException("BUSINESS_RULE_VIOLATION", "invalid store status transition");
        }
        StoreView changed = repository.changeStoreStatus(
                publicId, targetStatus, current.version(), actor.staffId(), traceId);
        if (changed == null) throw new OrganizationException("VERSION_CONFLICT", "store changed concurrently");
        return changed;
    }

    private static void requirePermission(TenantAccessContext actor, String permission) {
        if (actor == null || !actor.permissionCodes().contains(permission)) {
            throw new OrganizationException("PERMISSION_DENIED", "missing permission: " + permission);
        }
    }

    private static void validateRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new OrganizationException("BUSINESS_RULE_VIOLATION", field + " is required");
        }
    }

    public record UpdateEnterpriseCommand(
            String name, String legalName, String contactName, String contactPhone) {}

    public record CreateStoreCommand(String storeNo, String name, String regionCode, String address) {}

    public record EnterpriseView(
            String publicId, String orgNo, String name, String legalName,
            String contactName, String contactPhone, long version) {}

    public record StoreView(
            String publicId, String storeNo, String name, String status, long version, boolean operational) {}
}
