package com.smartelder.core.licensing;

import com.smartelder.core.api.access.TenantAccessContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.smartelder.module.api.ModuleRegistry;

@Service
public class TenantModuleService {
    private final TenantModuleRepository repository;
    private final Clock clock;
    private final ModuleRegistry registry;
    public TenantModuleService(TenantModuleRepository repository) {
        this(repository, Clock.systemUTC(), null);
    }
    @Autowired
    public TenantModuleService(TenantModuleRepository repository, Clock clock, ModuleRegistry registry) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<TenantModuleRepository.ModuleSummary> listModules(TenantAccessContext actor) {
        require(actor, "core.module.view");
        return repository.listModules().stream()
                .filter(item -> registry == null || registry.contains(item.moduleCode())).toList();
    }

    @Transactional(readOnly = true)
    public List<TenantModuleRepository.AssignmentView> listAssignments(
            String moduleCode, TenantAccessContext actor) {
        require(actor, "core.module.view");
        requireModule(moduleCode);
        return repository.listAssignments(moduleCode);
    }

    @Transactional(readOnly = true)
    public List<TenantModuleRepository.StoreRef> listAssignableStores(TenantAccessContext actor) {
        require(actor, "core.module.assignment.manage");
        return repository.listActiveStores();
    }

    @Transactional
    public void setActivation(String moduleCode, boolean enabled, TenantAccessContext actor, String traceId) {
        require(actor, "core.module.manage");
        requireModule(moduleCode);
        if (enabled) {
            var grant = grant(moduleCode);
            requireEntitled(grant);
            for (String dependency : dependencies(moduleCode)) {
                var dependencyGrant = findGrant(dependency);
                if (dependencyGrant == null || !"FORMAL".equals(dependencyGrant.licenseType())
                        || !"ACTIVE".equals(dependencyGrant.status()) || expired(dependencyGrant)) {
                    throw new TenantModuleException("MODULE_DEPENDENCY_UNSATISFIED",
                            "formal module dependency is not currently licensed: " + dependency);
                }
            }
        }
        repository.activation(moduleCode, enabled, actor.staffId(), traceId);
    }

    @Transactional
    public TenantModuleRepository.AssignmentView assignStore(String moduleCode, String storePublicId,
            String idempotencyKey, TenantAccessContext actor, String traceId) {
        require(actor, "core.module.assignment.manage");
        requireModule(moduleCode);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new TenantModuleException("BUSINESS_RULE_VIOLATION", "valid Idempotency-Key is required");
        }
        requireEntitled(grant(moduleCode));
        if (!repository.enabledModules().contains(moduleCode)) {
            throw new TenantModuleException("MODULE_DISABLED", "module is not enabled by headquarters");
        }
        for (String dependency : dependencies(moduleCode)) {
            var dependencyGrant = findGrant(dependency);
            if (dependencyGrant == null || !"FORMAL".equals(dependencyGrant.licenseType())
                    || !"ACTIVE".equals(dependencyGrant.status()) || expired(dependencyGrant)
                    || !repository.enabledModules().contains(dependency)) {
                throw new TenantModuleException("MODULE_DEPENDENCY_UNSATISFIED", "module dependency is unavailable");
            }
        }
        TenantModuleRepository.StoreRef store = repository.findStore(storePublicId);
        if (store == null) throw new TenantModuleException("NOT_FOUND", "store not found");
        return repository.assign(moduleCode, storePublicId, idempotencyKey, actor.staffId(), traceId);
    }

    @Transactional
    public void revokeAssignment(String moduleCode, String storePublicId, TenantAccessContext actor, String traceId) {
        require(actor, "core.module.assignment.manage");
        requireModule(moduleCode);
        repository.revoke(moduleCode, storePublicId, actor.staffId(), traceId);
    }

    private TenantLicenseRepository.ModuleGrant grant(String code) {
        var grant = findGrant(code);
        if (grant == null) throw new TenantModuleException("MODULE_NOT_LICENSED", "module is not licensed");
        return grant;
    }
    private TenantLicenseRepository.ModuleGrant findGrant(String code) {
        return repository.entitlements().stream().filter(item -> item.moduleCode().equals(code)).findFirst().orElse(null);
    }
    private Set<String> dependencies(String moduleCode) {
        if (registry == null || !registry.contains(moduleCode)) return Set.of();
        return registry.require(moduleCode).dependencies().stream()
                .map(com.smartelder.module.api.ModuleDependency::moduleCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private boolean expired(TenantLicenseRepository.ModuleGrant grant) {
        return !grant.perpetual() && grant.expiresAt() != null && clock.instant().isAfter(grant.expiresAt());
    }
    private void requireEntitled(TenantLicenseRepository.ModuleGrant grant) {
        if (!"ACTIVE".equals(grant.status()) || clock.instant().isBefore(grant.startsAt()) || expired(grant)) {
            throw new TenantModuleException("MODULE_NOT_LICENSED", "module entitlement is not active");
        }
    }
    private void requireModule(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new TenantModuleException("NOT_FOUND", "module not found");
        if (registry != null && !registry.contains(code))
            throw new TenantModuleException("NOT_FOUND", "module is not registered in this tenant application");
    }
    private static void require(TenantAccessContext actor, String permission) {
        if (actor == null || !"HQ".equals(actor.staffType()) || !actor.permissionCodes().contains(permission))
            throw new TenantModuleException("PERMISSION_DENIED", "missing permission: " + permission);
        if (!"ALL".equals(actor.dataScope().type()))
            throw new TenantModuleException("DATA_SCOPE_DENIED", "operation requires all-store data scope");
    }
}
