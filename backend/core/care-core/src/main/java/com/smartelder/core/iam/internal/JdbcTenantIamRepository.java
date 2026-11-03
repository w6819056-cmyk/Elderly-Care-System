package com.smartelder.core.iam.internal;

import com.smartelder.core.iam.TenantIamService.CreatePermissionGroupCommand;
import com.smartelder.core.iam.TenantIamService.CreateRoleCommand;
import com.smartelder.core.iam.TenantIamService.CreateStaffCommand;
import com.smartelder.core.iam.TenantIamService.PermissionDefinitionView;
import com.smartelder.core.iam.TenantIamService.PermissionGroupView;
import com.smartelder.core.iam.TenantIamService.RoleView;
import com.smartelder.core.iam.TenantIamService.StaffView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantIamRepository {

    private final JdbcClient jdbc;

    public JdbcTenantIamRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Set<String> groupPermissionCodes(long id) {
        return Set.copyOf(jdbc.sql("SELECT d.permission_code FROM iam_permission_group_item m JOIN iam_permission_definition d ON d.id=m.permission_definition_id WHERE m.permission_group_id=:id").param("id",id).query(String.class).list());
    }
    public Set<String> roleGroupCodes(long id) {
        return Set.copyOf(jdbc.sql("SELECT g.group_code FROM iam_role_permission_group r JOIN iam_permission_group g ON g.id=r.permission_group_id WHERE r.role_id=:id").param("id",id).query(String.class).list());
    }
    public Map<String,String> roleEffects(long id) {
        Map<String,String> result=new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT d.permission_code,r.effect FROM iam_role_permission r JOIN iam_permission_definition d ON d.id=r.permission_definition_id WHERE r.role_id=:id").param("id",id).query((rs,n)->{result.put(rs.getString(1),rs.getString(2));return rs.getString(1);}).list();
        return result;
    }

    public Set<String> staffRoleCodes(long id) {return Set.copyOf(jdbc.sql("SELECT r.role_code FROM iam_staff_role s JOIN iam_role r ON r.id=s.role_id WHERE s.staff_id=:id").param("id",id).query(String.class).list());}
    public String staffScopeType(long id) {return jdbc.sql("SELECT scope_type FROM iam_staff_scope_config WHERE staff_id=:id").param("id",id).query(String.class).optional().orElse("SELF");}
    public Set<String> staffStorePublicIds(long id) {return Set.copyOf(jdbc.sql("SELECT s.public_id FROM iam_staff_store_scope c JOIN core_store s ON s.id=c.store_id WHERE c.staff_id=:id").param("id",id).query(String.class).list());}
    public Set<String> staffGroupPublicIds(long id) {return Set.copyOf(jdbc.sql("SELECT g.public_id FROM iam_staff_store_group_scope c JOIN iam_store_group g ON g.id=c.store_group_id WHERE c.staff_id=:id").param("id",id).query(String.class).list());}

    public Set<String> activePermissionCodes() {
        return Set.copyOf(jdbc.sql("SELECT permission_code FROM iam_permission_definition WHERE active = TRUE")
                .query(String.class).list());
    }

    public List<PermissionDefinitionView> listPermissionDefinitions() {
        return jdbc.sql("SELECT permission_code, module_code, name, description FROM iam_permission_definition "
                        + "WHERE active = TRUE ORDER BY module_code, permission_code")
                .query((rs, row) -> new PermissionDefinitionView(rs.getString("permission_code"),
                        rs.getString("module_code"), rs.getString("name"), rs.getString("description"))).list();
    }

    public List<PermissionGroupView> listPermissionGroups() {
        return jdbc.sql("SELECT id, public_id, group_code, name, description, status, version "
                        + "FROM iam_permission_group ORDER BY group_code")
                .query((rs, row) -> new PermissionGroupView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("group_code"), rs.getString("name"), rs.getString("description"),
                        rs.getString("status"), rs.getLong("version"))).list();
    }

    public PermissionGroupView createPermissionGroup(
            CreatePermissionGroupCommand command, long actorId, String traceId) {
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_permission_group (public_id, group_code, name, description, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, :code, :name, :description, 'ACTIVE', 0, :now, :now)")
                .param("publicId", publicId).param("code", command.groupCode()).param("name", command.name())
                .param("description", command.description()).param("now", now).update();
        long id = groupId(publicId);
        replacePermissionGroupPermissions(id, command.permissionCodes());
        audit(actorId, "PERMISSION_GROUP_CREATED", "PERMISSION_GROUP", publicId, traceId);
        return findPermissionGroup(publicId);
    }

    public PermissionGroupView findPermissionGroup(String publicId) {
        return jdbc.sql("SELECT id, public_id, group_code, name, description, status, version "
                        + "FROM iam_permission_group WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query((rs, row) -> new PermissionGroupView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("group_code"), rs.getString("name"), rs.getString("description"),
                        rs.getString("status"), rs.getLong("version"))).optional().orElse(null);
    }

    public PermissionGroupView findPermissionGroupByCode(String code) {
        return jdbc.sql("SELECT id, public_id, group_code, name, description, status, version "
                        + "FROM iam_permission_group WHERE group_code = :code AND status = 'ACTIVE'")
                .param("code", code)
                .query((rs, row) -> new PermissionGroupView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("group_code"), rs.getString("name"), rs.getString("description"),
                        rs.getString("status"), rs.getLong("version"))).optional().orElse(null);
    }

    public void replacePermissionGroupPermissions(long groupId, Set<String> codes) {
        jdbc.sql("DELETE FROM iam_permission_group_item WHERE permission_group_id = :groupId")
                .param("groupId", groupId).update();
        if (!codes.isEmpty()) {
            jdbc.sql("INSERT INTO iam_permission_group_item (permission_group_id, permission_definition_id) "
                            + "SELECT :groupId, id FROM iam_permission_definition WHERE permission_code IN (:codes) "
                            + "AND active = TRUE")
                    .param("groupId", groupId).param("codes", codes).update();
        }
        jdbc.sql("UPDATE iam_permission_group SET version = version + 1, updated_at = :now WHERE id = :groupId")
                .param("now", Timestamp.from(Instant.now())).param("groupId", groupId).update();
    }

    public RoleView createRole(CreateRoleCommand command, long actorId, String traceId) {
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_role (public_id, role_code, name, role_domain, protected, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, :code, :name, :domain, FALSE, 'ACTIVE', 0, :now, :now)")
                .param("publicId", publicId).param("code", command.roleCode()).param("name", command.name())
                .param("domain", command.roleDomain()).param("now", now).update();
        long roleId = roleId(publicId);
        for (String groupCode : command.permissionGroupCodes()) {
            jdbc.sql("INSERT INTO iam_role_permission_group (role_id, permission_group_id) "
                            + "SELECT :roleId, id FROM iam_permission_group WHERE group_code = :groupCode")
                    .param("roleId", roleId).param("groupCode", groupCode).update();
        }
        command.permissionEffects().forEach((code, effect) -> jdbc.sql(
                        "INSERT INTO iam_role_permission (role_id, permission_definition_id, effect) "
                                + "SELECT :roleId, id, :effect FROM iam_permission_definition WHERE permission_code = :code")
                .param("roleId", roleId).param("effect", effect).param("code", code).update());
        audit(actorId, "ROLE_CREATED", "ROLE", publicId, traceId);
        return findRoleByCode(command.roleCode());
    }

    public RoleView findRoleByCode(String code) {
        return jdbc.sql("SELECT id, public_id, role_code, name, role_domain, protected, status, version "
                        + "FROM iam_role WHERE role_code = :code AND status = 'ACTIVE'")
                .param("code", code)
                .query((rs, row) -> new RoleView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("role_code"), rs.getString("name"), rs.getString("role_domain"),
                        rs.getBoolean("protected"), rs.getString("status"), rs.getLong("version")))
                .optional().orElse(null);
    }

    public RoleView findRoleById(long id) {
        return jdbc.sql("SELECT id, public_id, role_code, name, role_domain, protected, status, version "
                        + "FROM iam_role WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new RoleView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("role_code"), rs.getString("name"), rs.getString("role_domain"),
                        rs.getBoolean("protected"), rs.getString("status"), rs.getLong("version")))
                .optional().orElse(null);
    }

    public RoleView findRoleByPublicId(String publicId) {
        return jdbc.sql("SELECT id, public_id, role_code, name, role_domain, protected, status, version "
                        + "FROM iam_role WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query((rs, row) -> new RoleView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("role_code"), rs.getString("name"), rs.getString("role_domain"),
                        rs.getBoolean("protected"), rs.getString("status"), rs.getLong("version")))
                .optional().orElse(null);
    }

    public List<RoleView> listRoles() {
        return jdbc.sql("SELECT id, public_id, role_code, name, role_domain, protected, status, version "
                        + "FROM iam_role ORDER BY role_domain, role_code")
                .query((rs, row) -> new RoleView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("role_code"), rs.getString("name"), rs.getString("role_domain"),
                        rs.getBoolean("protected"), rs.getString("status"), rs.getLong("version"))).list();
    }

    public void replaceRolePermissions(String publicId, Set<String> groupCodes, Map<String, String> effects,
                                       long actorId, String traceId) {
        long roleId = roleId(publicId);
        jdbc.sql("DELETE FROM iam_role_permission_group WHERE role_id = :roleId").param("roleId", roleId).update();
        jdbc.sql("DELETE FROM iam_role_permission WHERE role_id = :roleId").param("roleId", roleId).update();
        for (String groupCode : groupCodes) {
            jdbc.sql("INSERT INTO iam_role_permission_group (role_id, permission_group_id) "
                            + "SELECT :roleId, id FROM iam_permission_group WHERE group_code = :groupCode "
                            + "AND status = 'ACTIVE'")
                    .param("roleId", roleId).param("groupCode", groupCode).update();
        }
        effects.forEach((code, effect) -> jdbc.sql(
                        "INSERT INTO iam_role_permission (role_id, permission_definition_id, effect) "
                                + "SELECT :roleId, id, :effect FROM iam_permission_definition "
                                + "WHERE permission_code = :code AND active = TRUE")
                .param("roleId", roleId).param("effect", effect).param("code", code).update());
        jdbc.sql("UPDATE iam_role SET version = version + 1, updated_at = :now WHERE id = :roleId")
                .param("now", Timestamp.from(Instant.now())).param("roleId", roleId).update();
        audit(actorId, "ROLE_PERMISSIONS_REPLACED", "ROLE", publicId, traceId);
    }

    public StaffView findStaffByPublicId(String publicId) {
        return jdbc.sql("SELECT id, public_id, username, display_name, staff_type, home_store_id, status, version "
                        + "FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query((rs, row) -> new StaffView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("username"), rs.getString("display_name"), rs.getString("staff_type"),
                        rs.getObject("home_store_id", Long.class), rs.getString("status"), rs.getLong("version")))
                .optional().orElse(null);
    }

    public List<StaffView> listStaff() {
        return jdbc.sql("SELECT id, public_id, username, display_name, staff_type, home_store_id, status, version "
                        + "FROM iam_staff ORDER BY staff_type, username")
                .query((rs, row) -> new StaffView(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("username"), rs.getString("display_name"), rs.getString("staff_type"),
                        rs.getObject("home_store_id", Long.class), rs.getString("status"), rs.getLong("version")))
                .list();
    }

    public void replaceStaffRoles(String publicId, Set<String> roleCodes, long actorId, String traceId) {
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId AND status = 'ACTIVE'")
                .param("publicId", publicId).query(Long.class).single();
        replaceStaffRoles(staffId, roleCodes);
        jdbc.sql("UPDATE iam_staff SET version = version + 1, updated_at = :now WHERE id = :staffId")
                .param("now", Timestamp.from(Instant.now())).param("staffId", staffId).update();
        audit(actorId, "STAFF_ROLES_REPLACED", "STAFF", publicId, traceId);
    }

    public boolean isProtectedOwnerStaff(String publicId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM iam_staff s JOIN iam_staff_role sr ON sr.staff_id = s.id "
                        + "JOIN iam_role r ON r.id = sr.role_id WHERE s.public_id = :publicId "
                        + "AND r.role_code = 'TENANT_OWNER' AND r.protected = TRUE")
                .param("publicId", publicId).query(Integer.class).single();
        return count > 0;
    }

    public Long requireActiveStoreId(String publicId) {
        Long id = jdbc.sql("SELECT id FROM core_store WHERE public_id = :publicId AND status = 'ACTIVE'")
                .param("publicId", publicId).query(Long.class).optional().orElse(null);
        if (id == null) throw new IllegalArgumentException("home store is missing or inactive");
        return id;
    }

    public StaffView createStaff(CreateStaffCommand command, Long homeStoreId, long actorId, String traceId) {
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_staff (public_id, username, password_hash, display_name, phone, staff_type, "
                        + "home_store_id, status, version, created_at, updated_at) VALUES (:publicId, :username, "
                        + ":passwordHash, :displayName, :phone, :type, :homeStoreId, 'ACTIVE', 0, :now, :now)")
                .param("publicId", publicId).param("username", command.username())
                .param("passwordHash", command.passwordHash()).param("displayName", command.displayName())
                .param("phone", command.phone()).param("type", command.staffType())
                .param("homeStoreId", homeStoreId).param("now", now).update();
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
        replaceStaffRoles(staffId, command.roleCodes());
        audit(actorId, "STAFF_CREATED", "STAFF", publicId, traceId);
        return new StaffView(staffId, publicId, command.username(), command.displayName(),
                command.staffType(), homeStoreId, "ACTIVE", 0);
    }

    public void replaceStaffRoles(long staffId, Set<String> roleCodes) {
        jdbc.sql("DELETE FROM iam_staff_role WHERE staff_id = :staffId").param("staffId", staffId).update();
        if (!roleCodes.isEmpty()) {
            jdbc.sql("INSERT INTO iam_staff_role (staff_id, role_id) SELECT :staffId, id FROM iam_role "
                            + "WHERE role_code IN (:codes) AND status = 'ACTIVE'")
                    .param("staffId", staffId).param("codes", roleCodes).update();
        }
    }

    public void disableStaff(String publicId, long actorId, String traceId) {
        int updated = jdbc.sql("UPDATE iam_staff SET status = 'DISABLED', version = version + 1, updated_at = :now "
                        + "WHERE public_id = :publicId AND status = 'ACTIVE'")
                .param("now", Timestamp.from(Instant.now())).param("publicId", publicId).update();
        if (updated == 0) throw new IllegalStateException("staff not found or already disabled");
        audit(actorId, "STAFF_DISABLED", "STAFF", publicId, traceId);
    }

    public void audit(long actorId, String action, String targetType, String publicId, String traceId) {
        jdbc.sql("INSERT INTO audit_tenant_log (actor_staff_id, action, target_type, target_public_id, trace_id, "
                        + "details_json, created_at) VALUES (:actor, :action, :targetType, :publicId, :traceId, '{}', :now)")
                .param("actor", actorId).param("action", action).param("targetType", targetType)
                .param("publicId", publicId).param("traceId", traceId)
                .param("now", Timestamp.from(Instant.now())).update();
    }

    private long groupId(String publicId) {
        return jdbc.sql("SELECT id FROM iam_permission_group WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
    }

    private long roleId(String publicId) {
        return jdbc.sql("SELECT id FROM iam_role WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
    }

}
