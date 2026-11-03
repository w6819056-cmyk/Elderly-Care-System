package com.smartelder.core.licensing;

import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleRegistry;
import com.smartelder.module.api.ModuleResourceOperation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ModuleAccessQuery {
    private final JdbcClient jdbc;
    private final ModuleRegistry registry;
    public ModuleAccessQuery(JdbcClient jdbc, ModuleRegistry registry) { this.jdbc = jdbc; this.registry = registry; }

    public ModuleAccessDecision evaluate(String moduleCode, String resource, String operation, Long storeId, Instant now) {
        if ("core".equals(moduleCode)) {
            if (storeId != null && isCoreWritePermission(operation)) {
                String storeStatus = jdbc.sql("SELECT status FROM core_store WHERE id=:id")
                        .param("id", storeId).query(String.class).optional().orElse(null);
                if (!"ACTIVE".equals(storeStatus)) return deny("STORE_INACTIVE", "STORE_NOT_ACTIVE", 0);
            }
            return new ModuleAccessDecision("ACTIVE", true, null, 0);
        }
        if (!registry.contains(moduleCode)) return deny("UNREGISTERED", "MODULE_NOT_REGISTERED", 0);
        ModuleDescriptor descriptor = registry.require(moduleCode);
        ModuleResourceOperation metadata = descriptor.resourceOperations().stream()
                .filter(item -> item.resource().equals(resource) && item.operation().equals(operation)).findFirst().orElse(null);
        if (metadata == null) return deny("UNREGISTERED_OPERATION", "MODULE_OPERATION_NOT_REGISTERED", 0);
        Snapshot snapshot = jdbc.sql("SELECT license_version, offline_grace_days, last_successful_sync_at, platform_instance_status "
                        + "FROM lic_license_snapshot WHERE id=1")
                .query((rs, row) -> new Snapshot(rs.getLong("license_version"), rs.getInt("offline_grace_days"),
                        rs.getTimestamp("last_successful_sync_at").toInstant(), rs.getString("platform_instance_status")))
                .optional().orElse(null);
        Grant grant = jdbc.sql("SELECT license_type, status, starts_at, expires_at, perpetual FROM lic_module_entitlement WHERE module_code=:module")
                .param("module", moduleCode).query((rs, row) -> new Grant(rs.getString("license_type"), rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("expires_at") == null ? null
                                : rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("perpetual")))
                .optional().orElse(null);
        long version = snapshot == null ? 0 : snapshot.version();
        if (grant == null || !"ACTIVE".equals(grant.status()) || now.isBefore(grant.startsAt()))
            return deny("UNLICENSED", "MODULE_NOT_LICENSED", version);
        if (snapshot == null || !"ACTIVE".equals(snapshot.instanceStatus()))
            return deny("SUSPENDED", "ENTERPRISE_SUSPENDED", version);
        boolean enabled = jdbc.sql("SELECT enabled FROM core_enterprise_module WHERE module_code=:module")
                .param("module", moduleCode).query(Boolean.class).optional().orElse(false);
        if (!enabled) return deny("DISABLED", "MODULE_DISABLED", version);
        if (metadata.storeScoped()) {
            if (storeId == null) return deny("NOT_ASSIGNED", "MODULE_NOT_ASSIGNED", version);
            String storeStatus = jdbc.sql("SELECT status FROM core_store WHERE id=:id")
                    .param("id", storeId).query(String.class).optional().orElse(null);
            if (storeStatus == null) return deny("STORE_NOT_FOUND", "NOT_FOUND", version);
            boolean assigned = jdbc.sql("SELECT COUNT(*) FROM core_store_module_assignment WHERE module_code=:module "
                            + "AND store_id=:store AND status='ACTIVE'")
                    .param("module", moduleCode).param("store", storeId).query(Long.class).single() > 0;
            if (!assigned) return deny("NOT_ASSIGNED", "MODULE_NOT_ASSIGNED", version);
            if (!"ACTIVE".equals(storeStatus))
                return restrict("STORE_INACTIVE", metadata, !metadata.writeOperation(), version);
        }
        if (now.isAfter(snapshot.lastSync().plus(snapshot.offlineGraceDays(), ChronoUnit.DAYS)))
            return restrict("OFFLINE_READ_ONLY", metadata, !metadata.writeOperation(), version);
        if (!grant.perpetual() && grant.expiresAt() != null && now.isAfter(grant.expiresAt())) {
            if (now.isAfter(grant.expiresAt().plus(3, ChronoUnit.DAYS)))
                return restrict("READ_ONLY", metadata, !metadata.writeOperation(), version);
            return restrict("WIND_DOWN", metadata,
                    !metadata.writeOperation() || descriptor.windDownOperations().contains(metadata.key()), version);
        }
        for (var dependency : descriptor.dependencies()) {
            Boolean formal = jdbc.sql("SELECT COUNT(*) > 0 FROM lic_module_entitlement WHERE module_code=:module "
                            + "AND license_type='FORMAL' AND status='ACTIVE' AND starts_at<=:now "
                            + "AND (perpetual=TRUE OR expires_at IS NULL OR expires_at>=:now)")
                    .param("module", dependency.moduleCode()).param("now", java.sql.Timestamp.from(now))
                    .query(Boolean.class).single();
            if (!formal) return deny("DEPENDENCY_UNSATISFIED", "MODULE_DEPENDENCY_UNSATISFIED", version);
            boolean dependencyEnabled = jdbc.sql("SELECT enabled FROM core_enterprise_module WHERE module_code=:module")
                    .param("module", dependency.moduleCode()).query(Boolean.class).optional().orElse(false);
            if (!dependencyEnabled) return deny("DEPENDENCY_DISABLED", "MODULE_DEPENDENCY_UNSATISFIED", version);
        }
        return new ModuleAccessDecision("ACTIVE", true, null, version);
    }
    private static ModuleAccessDecision deny(String state, String code, long version) {
        return new ModuleAccessDecision(state, false, code, version);
    }
    private static boolean isCoreWritePermission(String permission) {
        if (permission == null) return false;
        String action = permission.substring(permission.lastIndexOf('.') + 1);
        return !Set.of("view", "read", "list", "query", "export").contains(action);
    }
    private static ModuleAccessDecision restrict(String state, ModuleResourceOperation operation,
            boolean allowed, long version) {
        return new ModuleAccessDecision(state, allowed,
                allowed ? null : "MODULE_READ_ONLY", version);
    }
    private record Snapshot(long version, int offlineGraceDays, Instant lastSync, String instanceStatus) {}
    private record Grant(String type, String status, Instant startsAt, Instant expiresAt, boolean perpetual) {}
}
