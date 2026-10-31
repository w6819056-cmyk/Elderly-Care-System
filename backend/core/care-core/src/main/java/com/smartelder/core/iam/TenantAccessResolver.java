package com.smartelder.core.iam;

import com.smartelder.core.api.access.TenantAccessContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TenantAccessResolver {

    private final JdbcClient jdbc;
    private final TenantDataScopeService dataScopeService;

    public TenantAccessResolver(JdbcClient jdbc, TenantDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
    }

    public TenantAccessContext resolve(long staffId) {
        StaffRow staff = jdbc.sql("SELECT id, public_id, staff_type, home_store_id FROM iam_staff "
                        + "WHERE id = :staffId AND status = 'ACTIVE'")
                .param("staffId", staffId)
                .query((rs, row) -> new StaffRow(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("staff_type"), rs.getObject("home_store_id", Long.class)))
                .optional().orElseThrow(() -> new IllegalStateException("staff account is inactive or missing"));

        List<RoleRow> roles = jdbc.sql("SELECT r.id, r.role_code, r.role_domain, r.protected "
                        + "FROM iam_staff_role sr JOIN iam_role r ON r.id = sr.role_id "
                        + "WHERE sr.staff_id = :staffId AND r.status = 'ACTIVE'")
                .param("staffId", staffId)
                .query((rs, row) -> new RoleRow(rs.getLong("id"), rs.getString("role_code"),
                        rs.getString("role_domain"), rs.getBoolean("protected"))).list();

        Set<String> roleCodes = new LinkedHashSet<>();
        boolean owner = false;
        for (RoleRow role : roles) {
            TenantIamService.requireRoleDomain(staff.type(), role.domain());
            roleCodes.add(role.code());
            owner |= role.protectedRole() && "TENANT_OWNER".equals(role.code());
        }
        Set<String> registered = Set.copyOf(jdbc.sql(
                "SELECT permission_code FROM iam_permission_definition WHERE active = TRUE")
                .query(String.class).list());
        Set<String> permissions;
        if (owner) {
            permissions = registered;
        } else {
            Set<String> union = new LinkedHashSet<>();
            if (!roles.isEmpty()) {
                union.addAll(jdbc.sql("SELECT DISTINCT pd.permission_code FROM iam_staff_role sr "
                                + "JOIN iam_role_permission_group rpg ON rpg.role_id = sr.role_id "
                                + "JOIN iam_permission_group pg ON pg.id = rpg.permission_group_id AND pg.status = 'ACTIVE' "
                                + "JOIN iam_permission_group_item pgi ON pgi.permission_group_id = pg.id "
                                + "JOIN iam_permission_definition pd ON pd.id = pgi.permission_definition_id "
                                + "AND pd.active = TRUE WHERE sr.staff_id = :staffId")
                        .param("staffId", staffId).query(String.class).list());
                Map<String, String> effects = new LinkedHashMap<>();
                jdbc.sql("SELECT pd.permission_code, rp.effect FROM iam_staff_role sr "
                                + "JOIN iam_role_permission rp ON rp.role_id = sr.role_id "
                                + "JOIN iam_permission_definition pd ON pd.id = rp.permission_definition_id "
                                + "AND pd.active = TRUE WHERE sr.staff_id = :staffId")
                        .param("staffId", staffId).query((rs, row) -> {
                            effects.merge(rs.getString("permission_code"), rs.getString("effect"),
                                    (left, right) -> "DENY".equals(left) || "DENY".equals(right) ? "DENY" : "ALLOW");
                            return 1;
                        }).list();
                permissions = TenantIamService.resolvePermissions(List.of(union), effects, registered);
            } else {
                permissions = Set.of();
            }
        }

        var scope = owner && "HQ".equals(staff.type())
                ? new com.smartelder.core.api.access.DataScope("ALL", Set.of())
                : dataScopeService.resolve(staff.id(), staff.type(), staff.homeStoreId());
        return new TenantAccessContext(staff.id(), staff.publicId(), staff.type(), staff.homeStoreId(),
                roleCodes, permissions, scope);
    }

    private record StaffRow(long id, String publicId, String type, Long homeStoreId) {}
    private record RoleRow(long id, String code, String domain, boolean protectedRole) {}
}
