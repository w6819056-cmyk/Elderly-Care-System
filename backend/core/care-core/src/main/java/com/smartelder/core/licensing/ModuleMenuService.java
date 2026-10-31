package com.smartelder.core.licensing;

import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.module.api.ModuleRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ModuleMenuService {
    private final ModuleRegistry registry;
    private final ModuleAccessQuery accessQuery;
    private final java.time.Clock clock;
    public ModuleMenuService(ModuleRegistry registry, ModuleAccessQuery accessQuery, java.time.Clock clock) {
        this.registry = registry; this.accessQuery = accessQuery; this.clock = clock;
    }
    public List<MenuView> list(TenantAccessContext actor, Long requestedStoreId) {
        if (actor == null) throw new TenantModuleException("PERMISSION_DENIED", "staff context is required");
        Long storeId = requestedStoreId;
        if ("STORE".equals(actor.staffType())) {
            if (requestedStoreId != null && !requestedStoreId.equals(actor.homeStoreId()))
                throw new TenantModuleException("DATA_SCOPE_DENIED", "store is outside the current data scope");
            storeId = actor.homeStoreId();
        } else if (requestedStoreId != null && !"ALL".equals(actor.dataScope().type())
                && !actor.dataScope().storeIds().contains(requestedStoreId)) {
            throw new TenantModuleException("DATA_SCOPE_DENIED", "store is outside the current data scope");
        }
        List<MenuView> result = new ArrayList<>();
        for (var module : registry.all().stream()
                .sorted(java.util.Comparator.comparingInt(com.smartelder.module.api.ModuleDescriptor::menuOrder)
                        .thenComparing(com.smartelder.module.api.ModuleDescriptor::code)).toList()) {
            for (var menu : module.menuEntries()) {
                if (!actor.permissionCodes().contains(menu.permissionCode())) continue;
                var read = module.resourceOperations().stream()
                        .filter(item -> item.permissionCode().equals(menu.permissionCode()) && !item.writeOperation())
                        .findFirst().orElse(null);
                if (read == null || (read.storeScoped() && storeId == null)) continue;
                ModuleAccessDecision decision = accessQuery.evaluate(module.code(), read.resource(), read.operation(), storeId,
                        clock.instant());
                if (decision.allowed()) result.add(new MenuView(module.code(), menu.key(), menu.label(),
                        menu.route(), menu.icon(), decision.state()));
            }
        }
        return List.copyOf(result);
    }
    public record MenuView(String moduleCode, String key, String label, String route, String icon, String state) {}
}
