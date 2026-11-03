package com.smartelder.api.tenant.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantBootstrapService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public TenantBootstrapService(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public BootstrapResult bootstrap(BootstrapCommand command) {
        validate(command);
        Integer initialized = jdbc.sql("SELECT COUNT(*) FROM core_enterprise")
                .query(Integer.class).single();
        if (initialized != 0) return BootstrapResult.ALREADY_INITIALIZED;

        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        String enterprisePublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();
        String staffPublicId = UUID.randomUUID().toString();
        String permissionGroupPublicId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO core_enterprise (id, public_id, org_no, name, status, version, created_at, updated_at) "
                        + "VALUES (1, :publicId, :orgNo, :name, 'ACTIVE', 0, :now, :now)")
                .param("publicId", enterprisePublicId).param("orgNo", command.orgNo())
                .param("name", command.enterpriseName()).param("now", timestamp).update();

        jdbc.sql("INSERT INTO iam_role (public_id, role_code, name, role_domain, protected, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, 'TENANT_OWNER', '企业所有者', 'HQ', TRUE, "
                        + "'ACTIVE', 0, :now, :now)")
                .param("publicId", rolePublicId).param("now", timestamp).update();
        long roleId = jdbc.sql("SELECT id FROM iam_role WHERE public_id = :publicId")
                .param("publicId", rolePublicId).query(Long.class).single();

        jdbc.sql("INSERT INTO iam_permission_group (public_id, group_code, name, description, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, 'TENANT_OWNER_ALL', '企业所有者权限', "
                        + "'由系统初始化并受保护', 'ACTIVE', 0, :now, :now)")
                .param("publicId", permissionGroupPublicId).param("now", timestamp).update();
        long groupId = jdbc.sql("SELECT id FROM iam_permission_group WHERE public_id = :publicId")
                .param("publicId", permissionGroupPublicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_permission_group_item (permission_group_id, permission_definition_id) "
                        + "SELECT :groupId, id FROM iam_permission_definition WHERE active = TRUE")
                .param("groupId", groupId).update();
        jdbc.sql("INSERT INTO iam_role_permission_group (role_id, permission_group_id) VALUES (:roleId, :groupId)")
                .param("roleId", roleId).param("groupId", groupId).update();

        jdbc.sql("INSERT INTO iam_staff (public_id, username, password_hash, display_name, staff_type, status, "
                        + "version, created_at, updated_at) VALUES (:publicId, :username, :passwordHash, :displayName, "
                        + "'HQ', 'ACTIVE', 0, :now, :now)")
                .param("publicId", staffPublicId).param("username", command.ownerAccount())
                .param("passwordHash", passwordEncoder.encode(command.ownerPassword()))
                .param("displayName", command.ownerDisplayName()).param("now", timestamp).update();
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", staffPublicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_role (staff_id, role_id) VALUES (:staffId, :roleId)")
                .param("staffId", staffId).param("roleId", roleId).update();
        jdbc.sql("INSERT INTO audit_tenant_log (actor_staff_id, action, target_type, target_public_id, trace_id, "
                        + "details_json, created_at) VALUES (:staffId, 'TENANT_BOOTSTRAPPED', 'ENTERPRISE', "
                        + ":enterprisePublicId, 'bootstrap', '{}', :now)")
                .param("staffId", staffId).param("enterprisePublicId", enterprisePublicId).param("now", timestamp).update();
        return BootstrapResult.CREATED;
    }

    private static void validate(BootstrapCommand command) {
        if (command == null || blank(command.orgNo()) || blank(command.enterpriseName())
                || blank(command.ownerAccount()) || blank(command.ownerDisplayName())
                || command.ownerPassword() == null || command.ownerPassword().length() < 12) {
            throw new IllegalArgumentException("complete tenant bootstrap configuration is required");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public enum BootstrapResult { CREATED, ALREADY_INITIALIZED }

    public record BootstrapCommand(
            String orgNo, String enterpriseName, String ownerAccount, String ownerDisplayName, String ownerPassword) {}
}
