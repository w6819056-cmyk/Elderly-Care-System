package com.smartelder.core.licensing.internal;

import com.smartelder.core.licensing.TenantLicenseRepository;
import com.smartelder.core.licensing.TenantModuleException;
import com.smartelder.core.licensing.TenantModuleRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcTenantModuleRepository implements TenantModuleRepository {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public JdbcTenantModuleRepository(JdbcClient jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock; this.transactions = new TransactionTemplate(manager);
    }

    @Override public List<TenantLicenseRepository.ModuleGrant> entitlements() {
        return jdbc.sql("SELECT module_code, license_type, status, starts_at, expires_at, perpetual, store_quota FROM lic_module_entitlement")
                .query((rs, row) -> new TenantLicenseRepository.ModuleGrant(rs.getString("module_code"),
                        rs.getString("license_type"), rs.getString("status"), rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("perpetual"), rs.getInt("store_quota"))).list();
    }
    @Override public Set<String> enabledModules() {
        return Set.copyOf(jdbc.sql("SELECT module_code FROM core_enterprise_module WHERE enabled = TRUE")
                .query(String.class).list());
    }
    @Override public Set<String> dependencies(String moduleCode) { return Set.of(); }
    @Override public List<ModuleSummary> listModules() {
        return jdbc.sql("""
                SELECT e.module_code, e.license_type, e.status, e.store_quota,
                       COALESCE(SUM(CASE WHEN a.status = 'ACTIVE' THEN 1 ELSE 0 END), 0) assigned,
                       COALESCE(MAX(CASE WHEN m.enabled THEN 1 ELSE 0 END), 0) enabled
                FROM lic_module_entitlement e
                LEFT JOIN core_store_module_assignment a ON a.module_code = e.module_code
                LEFT JOIN core_enterprise_module m ON m.module_code = e.module_code
                GROUP BY e.module_code, e.license_type, e.status, e.store_quota ORDER BY e.module_code
                """).query((rs, row) -> new ModuleSummary(rs.getString("module_code"), rs.getString("license_type"),
                rs.getString("status"), rs.getInt("store_quota"), rs.getInt("assigned"), rs.getInt("enabled") == 1)).list();
    }
    @Override public List<AssignmentView> listAssignments(String moduleCode) {
        return jdbc.sql("""
                SELECT a.public_id, a.module_code, s.public_id store_public_id, s.name store_name,
                       a.status, a.idempotency_key
                FROM core_store_module_assignment a JOIN core_store s ON s.id = a.store_id
                WHERE a.module_code = :moduleCode ORDER BY a.assigned_at, a.id
                """).param("moduleCode", moduleCode).query((rs, row) -> new AssignmentView(
                rs.getString("public_id"), rs.getString("module_code"), rs.getString("store_public_id"),
                rs.getString("store_name"), rs.getString("status"), rs.getString("idempotency_key"))).list();
    }
    @Override public StoreRef findStore(String publicId) {
        return jdbc.sql("SELECT id, public_id, name, status FROM core_store WHERE public_id = :id")
                .param("id", publicId).query((rs, row) -> new StoreRef(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("name"), rs.getString("status"))).optional().orElse(null);
    }
    @Override public AssignmentView assign(String moduleCode, String storePublicId, String key, long actor, String trace) {
        return transactions.execute(status -> {
            Integer quota = jdbc.sql("SELECT store_quota FROM lic_module_entitlement WHERE module_code = :module FOR UPDATE")
                    .param("module", moduleCode).query(Integer.class).optional()
                    .orElseThrow(() -> new TenantModuleException("MODULE_NOT_LICENSED", "module is not licensed"));
            AssignmentView repeated = findAssignment("a.module_code = :module AND a.idempotency_key = :key", moduleCode, key);
            if (repeated != null) {
                if (!repeated.storePublicId().equals(storePublicId))
                    throw new TenantModuleException("IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key was used for another store");
                return repeated;
            }
            AssignmentView active = findAssignment("a.module_code = :module AND a.store_id = "
                    + "(SELECT id FROM core_store WHERE public_id = :store) AND a.status = 'ACTIVE'", moduleCode, storePublicId);
            if (active != null) return active;
            StoreRef store = findStore(storePublicId);
            if (store == null) throw new TenantModuleException("NOT_FOUND", "store not found");
            if (!"ACTIVE".equals(store.status()))
                throw new TenantModuleException("STORE_NOT_ACTIVE", "store must be active before module assignment");
            long used = jdbc.sql("SELECT COUNT(*) FROM core_store_module_assignment WHERE module_code = :module AND status = 'ACTIVE'")
                    .param("module", moduleCode).query(Long.class).single();
            if (used >= quota) throw new TenantModuleException("MODULE_QUOTA_EXCEEDED", "module quota exceeded");
            String publicId = UUID.randomUUID().toString();
            jdbc.sql("""
                    INSERT INTO core_store_module_assignment(public_id, module_code, store_id, status, idempotency_key,
                        assigned_by_staff_id, assigned_at, version)
                    VALUES (:id, :module, :store, 'ACTIVE', :key, :actor, :now, 0)
                    """).param("id", publicId).param("module", moduleCode).param("store", store.id())
                    .param("key", key).param("actor", actor).param("now", Timestamp.from(clock.instant())).update();
            audit(actor, "MODULE_STORE_ASSIGNED", moduleCode, storePublicId, trace);
            return new AssignmentView(publicId, moduleCode, storePublicId, store.name(), "ACTIVE", key);
        });
    }
    @Override public void activation(String moduleCode, boolean enabled, long actor, String trace) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO core_enterprise_module(module_code, enabled, version, updated_by_staff_id, updated_at)
                    VALUES (:module, :enabled, 0, :actor, :now)
                    ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), version = version + 1,
                        updated_by_staff_id = VALUES(updated_by_staff_id), updated_at = VALUES(updated_at)
                    """).param("module", moduleCode).param("enabled", enabled).param("actor", actor)
                    .param("now", Timestamp.from(clock.instant())).update();
            audit(actor, enabled ? "MODULE_ENABLED" : "MODULE_DISABLED", moduleCode, null, trace);
        });
    }
    @Override public void revoke(String moduleCode, String storePublicId, long actor, String trace) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.sql("""
                    UPDATE core_store_module_assignment SET status = 'REVOKED', revoked_by_staff_id = :actor,
                        revoked_at = :now, version = version + 1
                    WHERE module_code = :module AND store_id = (SELECT id FROM core_store WHERE public_id = :store)
                      AND status = 'ACTIVE'
                    """).param("actor", actor).param("now", Timestamp.from(clock.instant()))
                    .param("module", moduleCode).param("store", storePublicId).update();
            if (changed > 0) audit(actor, "MODULE_STORE_REVOKED", moduleCode, storePublicId, trace);
        });
    }
    private AssignmentView findAssignment(String predicate, String module, String value) {
        String valueName = predicate.contains("idempotency_key") ? "key" : "store";
        String sql = "SELECT a.public_id, a.module_code, s.public_id store_public_id, s.name store_name, a.status, a.idempotency_key "
                + "FROM core_store_module_assignment a JOIN core_store s ON s.id = a.store_id WHERE " + predicate;
        var spec = jdbc.sql(sql).param("module", module).param(valueName, value);
        return spec.query((rs, row) -> new AssignmentView(rs.getString("public_id"), rs.getString("module_code"),
                rs.getString("store_public_id"), rs.getString("store_name"), rs.getString("status"),
                rs.getString("idempotency_key"))).optional().orElse(null);
    }
    private void audit(long actor, String action, String module, String store, String trace) {
        jdbc.sql("INSERT INTO audit_tenant_log(actor_staff_id, action, target_type, target_public_id, trace_id, details_json, created_at) "
                + "VALUES (:actor, :action, 'MODULE', :store, :trace, :details, :now)")
                .param("actor", actor).param("action", action).param("store", store).param("trace", trace)
                .param("details", "{\"moduleCode\":\"" + module + "\"}")
                .param("now", Timestamp.from(clock.instant())).update();
    }
}
