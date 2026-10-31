package com.smartelder.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.auth.TenantBootstrapService;
import com.smartelder.api.tenant.auth.TenantStaffAuthService;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.core.iam.TenantIamService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIamIntegrationTest {

    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "core.enterprise.view", "core.enterprise.manage", "core.store.view", "core.store.manage",
            "iam.staff.view", "iam.staff.manage", "iam.role.view", "iam.role.manage",
            "iam.permission-group.view", "iam.permission-group.manage");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantStaffAuthService authService;
    @Autowired private TenantIamService iamService;
    @Autowired private TenantBootstrapService bootstrapService;
    @Autowired private ObjectMapper mapper;

    @Test
    void permissionGroupsAndRolesAcceptOnlyRegisteredPermissionsAndProtectOwner() throws Exception {
        String token = createHqManagerToken();
        String groupCode = "G_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        mvc.perform(post("/api/tenant/v1/hq/permission-groups")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupCode\":\"" + groupCode + "\",\"name\":\"员工查看\","
                                + "\"permissionCodes\":[\"iam.staff.view\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.groupCode").value(groupCode));

        mvc.perform(post("/api/tenant/v1/hq/permission-groups")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupCode\":\"G_BAD_" + UUID.randomUUID().toString().substring(0, 8)
                                .toUpperCase() + "\",\"name\":\"非法权限\",\"permissionCodes\":[\"custom.injected\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        String roleCode = "R_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        mvc.perform(post("/api/tenant/v1/hq/roles")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"" + roleCode + "\",\"name\":\"员工管理员\","
                                + "\"roleDomain\":\"HQ\",\"permissionGroupCodes\":[\"" + groupCode
                                + "\"],\"permissionEffects\":{\"iam.staff.manage\":\"DENY\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roleDomain").value("HQ"));

        bootstrapService.bootstrap(new TenantBootstrapService.BootstrapCommand(
                "ORG-" + UUID.randomUUID(), "初始化企业", "owner_" + UUID.randomUUID().toString().substring(0, 8),
                "企业所有者", "owner-password-123"));
        mvc.perform(post("/api/tenant/v1/hq/staff")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"escalation_" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"password\":\"escalation-password-123\",\"displayName\":\"不应成为所有者\","
                                + "\"staffType\":\"HQ\",\"roleCodes\":[\"TENANT_OWNER\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
        String ownerPublicId = jdbc.sql("SELECT public_id FROM iam_role WHERE role_code = 'TENANT_OWNER' AND protected = TRUE")
                .query(String.class).single();
        mvc.perform(put("/api/tenant/v1/hq/roles/{id}/permissions", ownerPublicId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionGroupCodes\":[],\"permissionEffects\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void staffRolesMustMatchHqOrStoreDomain() throws Exception {
        String token = createHqManagerToken();
        String storePublicId = insertStore("ACTIVE");
        String storeRoleCode = "STORE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        MvcResult roleResult = mvc.perform(post("/api/tenant/v1/hq/roles")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"" + storeRoleCode + "\",\"name\":\"門店員工\","
                                + "\"roleDomain\":\"STORE\",\"permissionGroupCodes\":[],"
                                + "\"permissionEffects\":{\"core.store.view\":\"ALLOW\"}}"))
                .andExpect(status().isOk()).andReturn();
        String rolePublicId = mapper.readTree(roleResult.getResponse().getContentAsString()).path("data").path("publicId").asText();
        String roleCode = jdbc.sql("SELECT role_code FROM iam_role WHERE public_id = :publicId")
                .param("publicId", rolePublicId).query(String.class).single();
        String storeStaffUsername = "st_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        mvc.perform(post("/api/tenant/v1/hq/staff")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + storeStaffUsername + "\",\"password\":\"store-password-123\","
                                + "\"displayName\":\"門店員工\",\"staffType\":\"STORE\","
                                + "\"homeStorePublicId\":\"" + storePublicId + "\",\"roleCodes\":[\"" + roleCode + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.staffType").value("STORE"));
        assertThat(authService.login(storeStaffUsername, "store-password-123").principal()
                .accessContext().dataScope().storeIds()).hasSize(1);

        mvc.perform(post("/api/tenant/v1/hq/staff")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hq_" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"password\":\"hq-password-12345\",\"displayName\":\"錯誤角色\","
                                + "\"staffType\":\"HQ\",\"roleCodes\":[\"" + roleCode + "\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void storeGroupScopeChangesImmediatelyAndIsEnforcedServerSide() throws Exception {
        String token = createHqManagerToken();
        String storeA = insertStore("ACTIVE");
        String storeB = insertStore("ACTIVE");
        MvcResult groupResult = mvc.perform(post("/api/tenant/v1/hq/store-groups")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupCode\":\"REGION_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                                + "\",\"name\":\"華東區\"}"))
                .andExpect(status().isOk()).andReturn();
        String groupPublicId = mapper.readTree(groupResult.getResponse().getContentAsString()).path("data").path("publicId").asText();
        mvc.perform(put("/api/tenant/v1/hq/store-groups/{id}/stores", groupPublicId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storePublicIds\":[\"" + storeA + "\"]}"))
                .andExpect(status().isOk());

        String staffUsername = "regional_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MvcResult staffResult = mvc.perform(post("/api/tenant/v1/hq/staff")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + staffUsername + "\",\"password\":\"regional-password-123\","
                                + "\"displayName\":\"区域经理\",\"staffType\":\"HQ\",\"roleCodes\":[]}"))
                .andExpect(status().isOk()).andReturn();
        String staffPublicId = mapper.readTree(staffResult.getResponse().getContentAsString()).path("data").path("publicId").asText();
        mvc.perform(put("/api/tenant/v1/hq/staff/{id}/data-scope", staffPublicId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"STORE_GROUP\",\"storeGroupPublicIds\":[\"" + groupPublicId + "\"]}"))
                .andExpect(status().isOk());
        var login = authService.login(staffUsername, "regional-password-123");
        assertThat(login.principal().accessContext().dataScope().storeIds()).hasSize(1);

        mvc.perform(put("/api/tenant/v1/hq/store-groups/{id}/stores", groupPublicId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storePublicIds\":[\"" + storeB + "\"]}"))
                .andExpect(status().isOk());
        assertThat(authService.resolveToken(login.accessToken()).accessContext().dataScope().storeIds())
                .containsExactly(jdbc.sql("SELECT id FROM core_store WHERE public_id = :publicId")
                        .param("publicId", storeB).query(Long.class).single());
        TenantAccessContext scopedStaffViewer = new TenantAccessContext(
                login.principal().accessContext().staffId(), login.principal().accessContext().staffPublicId(),
                "HQ", null, Set.of(), Set.of("iam.staff.view"),
                login.principal().accessContext().dataScope());
        assertThat(iamService.listStaff(scopedStaffViewer))
                .extracting(staff -> staff.publicId()).contains(staffPublicId);
    }

    private String createHqManagerToken() {
        String publicId = UUID.randomUUID().toString();
        String username = "iam_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_staff (public_id, username, password_hash, display_name, staff_type, status, "
                        + "version, created_at, updated_at) VALUES (:publicId, :username, :hash, '总部管理', 'HQ', 'ACTIVE', 0, :now, :now)")
                .param("publicId", publicId).param("username", username)
                .param("hash", passwordEncoder.encode("iam-admin-password-123")).param("now", now).update();
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_scope_config (staff_id, scope_type, version, updated_at) "
                        + "VALUES (:staffId, 'ALL', 0, :now)")
                .param("staffId", staffId).param("now", now).update();
        String rolePublicId = UUID.randomUUID().toString();
        String roleCode = "IAM_ADMIN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.sql("INSERT INTO iam_role (public_id, role_code, name, role_domain, protected, status, version, created_at, updated_at) "
                        + "VALUES (:publicId, :code, '总部管理员', 'HQ', FALSE, 'ACTIVE', 0, :now, :now)")
                .param("publicId", rolePublicId).param("code", roleCode).param("now", now).update();
        long roleId = jdbc.sql("SELECT id FROM iam_role WHERE public_id = :publicId")
                .param("publicId", rolePublicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_role (staff_id, role_id) VALUES (:staffId, :roleId)")
                .param("staffId", staffId).param("roleId", roleId).update();
        for (String permission : ADMIN_PERMISSIONS) {
            long permissionId = jdbc.sql("SELECT id FROM iam_permission_definition WHERE permission_code = :code")
                    .param("code", permission).query(Long.class).single();
            jdbc.sql("INSERT INTO iam_role_permission (role_id, permission_definition_id, effect) "
                            + "VALUES (:roleId, :permissionId, 'ALLOW')")
                    .param("roleId", roleId).param("permissionId", permissionId).update();
        }
        return authService.login(username, "iam-admin-password-123").accessToken();
    }

    private String insertStore(String statusValue) {
        String publicId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO core_store (public_id, store_no, name, status, version, created_at, updated_at) "
                        + "VALUES (:publicId, :storeNo, '测试门店', :status, 0, :now, :now)")
                .param("publicId", publicId).param("storeNo", "T_" + UUID.randomUUID())
                .param("status", statusValue).param("now", now).update();
        return publicId;
    }
}
