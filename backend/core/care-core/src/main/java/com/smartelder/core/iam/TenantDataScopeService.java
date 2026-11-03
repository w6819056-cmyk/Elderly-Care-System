package com.smartelder.core.iam;

import com.smartelder.core.api.access.DataScope;
import com.smartelder.core.api.access.TenantAccessContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantDataScopeService {

    private static final Pattern GROUP_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private final JdbcClient jdbc;

    public TenantDataScopeService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public DataScope resolve(long staffId, String staffType, Long homeStoreId) {
        if ("STORE".equals(staffType)) {
            if (homeStoreId == null || homeStoreId <= 0) throw new IllegalStateException("STORE staff has no home store");
            return new DataScope("STORES", Set.of(homeStoreId));
        }
        if (!"HQ".equals(staffType) || homeStoreId != null) {
            throw new IllegalStateException("invalid staff organization binding");
        }
        String type = jdbc.sql("SELECT scope_type FROM iam_staff_scope_config WHERE staff_id = :staffId")
                .param("staffId", staffId).query(String.class).optional().orElse("SELF");
        return switch (type) {
            case "ALL", "SELF" -> new DataScope(type, Set.of());
            case "STORES" -> new DataScope(type, resolveExplicitStores(staffId));
            case "STORE_GROUP" -> new DataScope(type, resolveGroupStores(staffId));
            default -> throw new IllegalStateException("invalid persisted data scope");
        };
    }

    public void requireStoreAccess(TenantAccessContext context, long storeId, boolean write) {
        if (context == null) throw new IllegalStateException("DATA_SCOPE_DENIED");
        requireStoreAccess(context.dataScope(), storeId, write);
    }

    public void requireStoreAccess(DataScope scope, long storeId, boolean write) {
        String status = jdbc.sql("SELECT status FROM core_store WHERE id = :storeId")
                .param("storeId", storeId).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("STORE_NOT_FOUND"));
        boolean permitted = "ALL".equals(scope.type()) || scope.storeIds().contains(storeId);
        if (!permitted) throw new IllegalStateException("DATA_SCOPE_DENIED");
        if (write && !"ACTIVE".equals(status)) throw new IllegalStateException("STORE_INACTIVE");
    }

    @Transactional
    public StoreGroupView createStoreGroup(CreateStoreGroupCommand command, TenantAccessContext actor, String traceId) {
        requireHqPermission(actor);
        if (command.groupCode() == null || !GROUP_CODE.matcher(command.groupCode()).matches()) {
            throw new IllegalArgumentException("invalid store group code");
        }
        requireText(command.name(), "name");
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_store_group (public_id, group_code, name, version, created_at, updated_at) "
                        + "VALUES (:publicId, :code, :name, 0, :now, :now)")
                .param("publicId", publicId).param("code", command.groupCode())
                .param("name", command.name()).param("now", now).update();
        audit(actor.staffId(), "STORE_GROUP_CREATED", "STORE_GROUP", publicId, traceId);
        return new StoreGroupView(publicId, command.groupCode(), command.name());
    }

    @Transactional
    public void replaceStoreGroupMembers(String publicId, Set<String> storePublicIds,
                                         TenantAccessContext actor, String traceId) {
        requireHqPermission(actor);
        requireAllStoreScope(actor);
        long groupId = jdbc.sql("SELECT id FROM iam_store_group WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
        Set<String> ids = Set.copyOf(storePublicIds == null ? Set.of() : storePublicIds);
        Set<Long> storeIds = lookupStoreIds(ids);
        if (storeIds.size() != ids.size()) throw new IllegalArgumentException("unknown store in group membership");
        jdbc.sql("DELETE FROM iam_store_group_member WHERE store_group_id = :groupId")
                .param("groupId", groupId).update();
        for (Long storeId : storeIds) {
            jdbc.sql("INSERT INTO iam_store_group_member (store_group_id, store_id) VALUES (:groupId, :storeId)")
                    .param("groupId", groupId).param("storeId", storeId).update();
        }
        jdbc.sql("UPDATE iam_store_group SET version = version + 1, updated_at = :now WHERE id = :groupId")
                .param("now", Timestamp.from(Instant.now())).param("groupId", groupId).update();
        audit(actor.staffId(), "STORE_GROUP_MEMBERS_REPLACED", "STORE_GROUP", publicId, traceId);
    }

    @Transactional
    public void replaceStaffScope(ReplaceStaffScopeCommand command, TenantAccessContext actor, String traceId) {
        requireHqPermission(actor);
        requireAllStoreScope(actor);
        if (!Set.of("ALL", "STORE_GROUP", "STORES", "SELF").contains(command.scopeType())) {
            throw new IllegalArgumentException("unsupported scopeType");
        }
        Long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId AND status = 'ACTIVE' "
                        + "AND staff_type = 'HQ' AND home_store_id IS NULL")
                .param("publicId", command.staffPublicId()).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("HQ staff not found"));
        Integer protectedOwner = jdbc.sql("SELECT COUNT(*) FROM iam_staff_role sr JOIN iam_role r ON r.id = sr.role_id "
                        + "WHERE sr.staff_id = :staffId AND r.role_code = 'TENANT_OWNER' AND r.protected = TRUE")
                .param("staffId", staffId).query(Integer.class).single();
        if (protectedOwner > 0) throw new IllegalStateException("protected owner scope cannot be changed");

        Set<String> storePublicIds = Set.copyOf(command.storePublicIds() == null ? Set.of() : command.storePublicIds());
        Set<String> groupPublicIds = Set.copyOf(command.storeGroupPublicIds() == null ? Set.of() : command.storeGroupPublicIds());
        if (("ALL".equals(command.scopeType()) || "SELF".equals(command.scopeType()))
                && (!storePublicIds.isEmpty() || !groupPublicIds.isEmpty())) {
            throw new IllegalArgumentException(command.scopeType() + " scope cannot include stores or groups");
        }
        if ("STORES".equals(command.scopeType()) && !groupPublicIds.isEmpty()) {
            throw new IllegalArgumentException("STORES scope cannot include groups");
        }
        if ("STORE_GROUP".equals(command.scopeType()) && !storePublicIds.isEmpty()) {
            throw new IllegalArgumentException("STORE_GROUP scope cannot include direct stores");
        }
        Set<Long> storeIds = lookupStoreIds(storePublicIds);
        if (storeIds.size() != storePublicIds.size()) throw new IllegalArgumentException("unknown store in scope");
        Set<Long> groupIds = lookupGroupIds(groupPublicIds);
        if (groupIds.size() != groupPublicIds.size()) throw new IllegalArgumentException("unknown store group in scope");

        Integer exists = jdbc.sql("SELECT COUNT(*) FROM iam_staff_scope_config WHERE staff_id = :staffId")
                .param("staffId", staffId).query(Integer.class).single();
        Timestamp now = Timestamp.from(Instant.now());
        if (exists == 0) {
            jdbc.sql("INSERT INTO iam_staff_scope_config (staff_id, scope_type, version, updated_at) "
                            + "VALUES (:staffId, :type, 0, :now)")
                    .param("staffId", staffId).param("type", command.scopeType()).param("now", now).update();
        } else {
            jdbc.sql("UPDATE iam_staff_scope_config SET scope_type = :type, version = version + 1, updated_at = :now "
                            + "WHERE staff_id = :staffId")
                    .param("staffId", staffId).param("type", command.scopeType()).param("now", now).update();
        }
        jdbc.sql("DELETE FROM iam_staff_store_scope WHERE staff_id = :staffId").param("staffId", staffId).update();
        jdbc.sql("DELETE FROM iam_staff_store_group_scope WHERE staff_id = :staffId").param("staffId", staffId).update();
        for (Long storeId : storeIds) {
            jdbc.sql("INSERT INTO iam_staff_store_scope (staff_id, store_id) VALUES (:staffId, :storeId)")
                    .param("staffId", staffId).param("storeId", storeId).update();
        }
        for (Long groupId : groupIds) {
            jdbc.sql("INSERT INTO iam_staff_store_group_scope (staff_id, store_group_id) VALUES (:staffId, :groupId)")
                    .param("staffId", staffId).param("groupId", groupId).update();
        }
        audit(actor.staffId(), "STAFF_SCOPE_REPLACED", "STAFF", command.staffPublicId(), traceId);
    }

    private Set<Long> resolveExplicitStores(long staffId) {
        return Set.copyOf(jdbc.sql("SELECT store_id FROM iam_staff_store_scope WHERE staff_id = :staffId")
                .param("staffId", staffId).query(Long.class).list());
    }

    private Set<Long> resolveGroupStores(long staffId) {
        return Set.copyOf(jdbc.sql("SELECT DISTINCT sgm.store_id FROM iam_staff_store_group_scope sg "
                        + "JOIN iam_store_group_member sgm ON sgm.store_group_id = sg.store_group_id "
                        + "WHERE sg.staff_id = :staffId")
                .param("staffId", staffId).query(Long.class).list());
    }

    private Set<Long> lookupStoreIds(Set<String> publicIds) {
        if (publicIds.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.sql("SELECT id FROM core_store WHERE public_id IN (:ids)")
                .param("ids", publicIds).query(Long.class).list());
    }

    private Set<Long> lookupGroupIds(Set<String> publicIds) {
        if (publicIds.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.sql("SELECT id FROM iam_store_group WHERE public_id IN (:ids)")
                .param("ids", publicIds).query(Long.class).list());
    }

    private void requireHqPermission(TenantAccessContext actor) {
        if (actor == null || !"HQ".equals(actor.staffType()) || !actor.permissionCodes().contains("iam.staff.manage")) {
            throw new IllegalStateException("missing permission: iam.staff.manage");
        }
    }

    private void requireAllStoreScope(TenantAccessContext actor) {
        if (!"ALL".equals(actor.dataScope().type())) {
            throw new IllegalStateException("missing permission: all-store data scope required");
        }
    }

    private void audit(long actorId, String action, String type, String publicId, String traceId) {
        jdbc.sql("INSERT INTO audit_tenant_log (actor_staff_id, action, target_type, target_public_id, trace_id, "
                        + "details_json, created_at) VALUES (:actor, :action, :type, :publicId, :traceId, '{}', :now)")
                .param("actor", actorId).param("action", action).param("type", type)
                .param("publicId", publicId).param("traceId", traceId)
                .param("now", Timestamp.from(Instant.now())).update();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    public record CreateStoreGroupCommand(String groupCode, String name) {}
    public record ReplaceStaffScopeCommand(String staffPublicId, String scopeType,
                                           Set<String> storePublicIds, Set<String> storeGroupPublicIds) {}
    public record StoreGroupView(String publicId, String groupCode, String name) {}
}
