package com.smartelder.core.licensing;

import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleRegistry;
import com.smartelder.module.api.ModuleResourceOperation;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class ModuleAccessGuard {
    private final ModuleAccessQuery query;
    private final ModuleRegistry registry;
    private final Clock clock;
    public ModuleAccessGuard(ModuleAccessQuery query, ModuleRegistry registry, Clock clock) {
        this.query = query; this.registry = registry; this.clock = clock;
    }
    public ModuleAccessDecision require(String moduleCode, String resource, String operation,
            TenantAccessContext actor, Long storeId) {
        if (actor == null) throw new TenantModuleException("PERMISSION_DENIED", "staff context is required");
        String permission;
        boolean storeScoped;
        if ("core".equals(moduleCode)) {
            permission = operation;
            storeScoped = false;
        } else {
            if (!registry.contains(moduleCode)) throw new TenantModuleException("MODULE_NOT_REGISTERED", "module is not registered");
            ModuleDescriptor descriptor = registry.require(moduleCode);
            ModuleResourceOperation mapping = descriptor.resourceOperations().stream()
                    .filter(item -> item.resource().equals(resource) && item.operation().equals(operation)).findFirst()
                    .orElseThrow(() -> new TenantModuleException("MODULE_OPERATION_NOT_REGISTERED", "module operation is not registered"));
            permission = mapping.permissionCode();
            storeScoped = mapping.storeScoped();
        }
        if (!actor.permissionCodes().contains(permission))
            throw new TenantModuleException("PERMISSION_DENIED", "missing permission: " + permission);
        Long effectiveStoreId = storeId;
        if (storeId != null && !"ALL".equals(actor.dataScope().type())
                && !actor.dataScope().storeIds().contains(storeId)
                && !("STORE".equals(actor.staffType()) && storeId.equals(actor.homeStoreId())))
            throw new TenantModuleException("DATA_SCOPE_DENIED", "store is outside the current data scope");
        if (storeScoped && "STORE".equals(actor.staffType())) {
            if (storeId != null && !storeId.equals(actor.homeStoreId()))
                throw new TenantModuleException("DATA_SCOPE_DENIED", "store is outside the current data scope");
            effectiveStoreId = actor.homeStoreId();
        } else if (storeScoped && storeId != null && !"ALL".equals(actor.dataScope().type())
                && !actor.dataScope().storeIds().contains(storeId)) {
            throw new TenantModuleException("DATA_SCOPE_DENIED", "store is outside the current data scope");
        }
        ModuleAccessDecision decision = query.evaluate(moduleCode, resource, operation, effectiveStoreId, clock.instant());
        if (!decision.allowed()) throw new TenantModuleException(decision.reasonCode(), decision.state());
        return decision;
    }
}
