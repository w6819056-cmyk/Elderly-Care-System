package com.smartelder.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.auth.TenantStaffAuthService;
import com.smartelder.api.tenant.auth.TenantBootstrapService;
import com.smartelder.security.JwtService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TenantStaffAuthIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService legacyJwtService;
    @Autowired private TenantStaffAuthService authService;
    @Autowired private TenantBootstrapService bootstrapService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void activeStaffCanLoginAndUseTenantStaffRoute() throws Exception {
        Staff staff = createStaff("ACTIVE");
        MvcResult login = mvc.perform(post("/api/tenant/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + staff.username() + "\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        String token = body.path("data").path("accessToken").asText();

        mvc.perform(get("/api/tenant/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffPublicId").value(staff.publicId()))
                .andExpect(jsonPath("$.data.staffType").value("HQ"));
    }

    @Test
    void legacyTokenCannotAuthenticateTenantStaffRoute() throws Exception {
        String legacyToken = legacyJwtService.issue(9001L, "ADMIN", 1L);

        mvc.perform(get("/api/tenant/v1/auth/me").header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disablingStaffImmediatelyInvalidatesPreviouslyIssuedToken() throws Exception {
        Staff staff = createStaff("ACTIVE");
        TenantStaffAuthService.LoginResult login = authService.login(staff.username(), "secret-pass");
        jdbc.sql("UPDATE iam_staff SET status = 'DISABLED' WHERE public_id = :publicId")
                .param("publicId", staff.publicId()).update();

        mvc.perform(get("/api/tenant/v1/auth/me")
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permissionChangesAreResolvedAgainForAnExistingToken() {
        Staff staff = createStaff("ACTIVE");
        String rolePublicId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO iam_role (public_id, role_code, name, role_domain, protected, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, :code, '临时角色', 'HQ', FALSE, 'ACTIVE', 0, :now, :now)")
                .param("publicId", rolePublicId).param("code", "R_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .param("now", Timestamp.from(Instant.now())).update();
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", staff.publicId()).query(Long.class).single();
        long roleId = jdbc.sql("SELECT id FROM iam_role WHERE public_id = :publicId")
                .param("publicId", rolePublicId).query(Long.class).single();
        long permissionId = jdbc.sql("SELECT id FROM iam_permission_definition WHERE permission_code = 'core.store.view'")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_role (staff_id, role_id) VALUES (:staffId, :roleId)")
                .param("staffId", staffId).param("roleId", roleId).update();
        jdbc.sql("INSERT INTO iam_role_permission (role_id, permission_definition_id, effect) "
                        + "VALUES (:roleId, :permissionId, 'ALLOW')")
                .param("roleId", roleId).param("permissionId", permissionId).update();
        TenantStaffAuthService.LoginResult login = authService.login(staff.username(), "secret-pass");
        assertThat(login.principal().accessContext().permissionCodes()).contains("core.store.view");

        jdbc.sql("DELETE FROM iam_role_permission WHERE role_id = :roleId").param("roleId", roleId).update();

        assertThat(authService.resolveToken(login.accessToken()).accessContext().permissionCodes())
                .doesNotContain("core.store.view");
    }

    @Test
    void wrongPasswordUsesTheSameUnauthorizedResponse() throws Exception {
        Staff staff = createStaff("ACTIVE");

        mvc.perform(post("/api/tenant/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + staff.username() + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void bootstrapRejectsIncompleteConfigurationBeforeWritingAnything() {
        int before = jdbc.sql("SELECT COUNT(*) FROM core_enterprise").query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bootstrapService.bootstrap(
                        new TenantBootstrapService.BootstrapCommand("ORG-1", "康养企业", "owner", null, "secret")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT COUNT(*) FROM core_enterprise")
                .query(Integer.class).single()).isEqualTo(before);
    }

    @Test
    void bootstrapIsIdempotentAndCreatesTheInitialOwner() {
        clearTenantBootstrapData();
        String orgNo = "ORG-" + UUID.randomUUID();
        TenantBootstrapService.BootstrapCommand command = new TenantBootstrapService.BootstrapCommand(
                orgNo, "康养企业", "owner_" + UUID.randomUUID().toString().substring(0, 8), "总部管理员", "owner-secret");
        TenantBootstrapService.BootstrapCommand replacement = new TenantBootstrapService.BootstrapCommand(
                "REPLACEMENT", "不应覆盖", "replacement-owner", "替换账号", "replacement-secret");

        assertThat(bootstrapService.bootstrap(command)).isEqualTo(TenantBootstrapService.BootstrapResult.CREATED);
        assertThat(bootstrapService.bootstrap(replacement))
                .isEqualTo(TenantBootstrapService.BootstrapResult.ALREADY_INITIALIZED);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT COUNT(*) FROM core_enterprise WHERE org_no = :orgNo")
                .param("orgNo", orgNo).query(Integer.class).single()).isEqualTo(1);
        assertThat(authService.login(command.ownerAccount(), command.ownerPassword()).principal().staffType())
                .isEqualTo("HQ");
    }

    private Staff createStaff(String status) {
        String publicId = UUID.randomUUID().toString();
        String username = "staff_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_staff (public_id, username, password_hash, display_name, staff_type, status, "
                        + "version, created_at, updated_at) VALUES (:publicId, :username, :hash, '测试员工', 'HQ', "
                        + ":status, 0, :now, :now)")
                .param("publicId", publicId).param("username", username)
                .param("hash", passwordEncoder.encode("secret-pass")).param("status", status)
                .param("now", now).update();
        return new Staff(publicId, username);
    }

    private void clearTenantBootstrapData() {
        jdbc.sql("DELETE FROM audit_tenant_log").update();
        jdbc.sql("DELETE FROM iam_staff_store_group_scope").update();
        jdbc.sql("DELETE FROM iam_staff_store_scope").update();
        jdbc.sql("DELETE FROM iam_store_group_member").update();
        jdbc.sql("DELETE FROM iam_role_permission").update();
        jdbc.sql("DELETE FROM iam_role_permission_group").update();
        jdbc.sql("DELETE FROM iam_staff_role").update();
        jdbc.sql("DELETE FROM iam_permission_group_item").update();
        jdbc.sql("DELETE FROM iam_staff_scope_config").update();
        jdbc.sql("DELETE FROM iam_staff").update();
        jdbc.sql("DELETE FROM iam_role").update();
        jdbc.sql("DELETE FROM iam_permission_group").update();
        jdbc.sql("DELETE FROM iam_store_group").update();
        jdbc.sql("DELETE FROM core_store").update();
        jdbc.sql("DELETE FROM core_enterprise").update();
    }

    private record Staff(String publicId, String username) {}
}
