package com.smartelder.platform.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class PlatformAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void superAdminManagesRolesAndAdminWhileOperatorCannotManageRoles() throws Exception {
        String superToken = login("admin", "ChangeMe-Platform-2026");

        String operatorRoleResponse = mvc.perform(post("/api/platform/v1/roles")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"PLATFORM_OPERATOR",
                                  "name":"平台运营",
                                  "permissionCodes":["platform.account.manage"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("PLATFORM_OPERATOR"))
                .andExpect(jsonPath("$.data.permissionCodes[0]").value("platform.account.manage"))
                .andReturn().getResponse().getContentAsString();
        String operatorRoleId = objectMapper.readTree(operatorRoleResponse)
                .path("data").path("publicId").asText();

        String operatorAdminResponse = mvc.perform(post("/api/platform/v1/admins")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"operator1",
                                  "displayName":"平台运营一号",
                                  "password":"OperatorPass-2026",
                                  "roleCodes":["PLATFORM_OPERATOR"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("operator1"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("PLATFORM_OPERATOR"))
                .andReturn().getResponse().getContentAsString();
        String operatorAdminId = objectMapper.readTree(operatorAdminResponse)
                .path("data").path("publicId").asText();

        String operatorToken = login("operator1", "OperatorPass-2026");
        mvc.perform(post("/api/platform/v1/roles")
                        .header("Authorization", bearer(operatorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"FORBIDDEN_ROLE","name":"不应创建","permissionCodes":[]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        String rolesResponse = mvc.perform(get("/api/platform/v1/roles")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'PLATFORM_SUPER_ADMIN')]").exists())
                .andReturn().getResponse().getContentAsString();
        String superRoleId = findPublicIdByCode(
                objectMapper.readTree(rolesResponse).path("data"),
                "PLATFORM_SUPER_ADMIN"
        );
        mvc.perform(put("/api/platform/v1/roles/{publicId}/permissions", superRoleId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCodes":[]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        String auditorRoleResponse = mvc.perform(post("/api/platform/v1/roles")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"PLATFORM_AUDITOR",
                                  "name":"平台审计员",
                                  "permissionCodes":["platform.audit.view"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String auditorRoleId = objectMapper.readTree(auditorRoleResponse)
                .path("data").path("publicId").asText();

        mvc.perform(put("/api/platform/v1/roles/{publicId}/permissions", auditorRoleId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "permissionCodes":["platform.audit.view","platform.enterprise.view"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissionCodes.length()").value(2));

        mvc.perform(post("/api/platform/v1/admins/{publicId}/roles", operatorAdminId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCodes":["PLATFORM_OPERATOR","PLATFORM_AUDITOR"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCodes.length()").value(2));

        mvc.perform(patch("/api/platform/v1/admins/{publicId}/status", operatorAdminId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mvc.perform(get("/api/platform/v1/admins")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.items[?(@.username == 'operator1')]").exists());

        Integer auditCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM pf_audit_log
                        WHERE action IN (
                            'PLATFORM_ROLE_CREATED',
                            'PLATFORM_ROLE_PERMISSIONS_REPLACED',
                            'PLATFORM_ADMIN_CREATED',
                            'PLATFORM_ADMIN_ROLES_REPLACED',
                            'PLATFORM_ADMIN_STATUS_CHANGED'
                        )
                        """)
                .query(Integer.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(auditCount).isGreaterThanOrEqualTo(5);
    }

    @Test
    void nonSuperAdminCannotGrantOrManageSuperAdminRole() throws Exception {
        String superToken = login("admin", "ChangeMe-Platform-2026");

        mvc.perform(post("/api/platform/v1/roles")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"PLATFORM_ESCALATION_MANAGER",
                                  "name":"提权测试角色",
                                  "permissionCodes":["platform.account.manage","platform.role.manage"]
                                }
                                """))
                .andExpect(status().isCreated());

        String escalatorResponse = mvc.perform(post("/api/platform/v1/admins")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"escalator1",
                                  "displayName":"提权测试账号",
                                  "password":"EscalatorPass-2026",
                                  "roleCodes":["PLATFORM_ESCALATION_MANAGER"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String escalatorId = objectMapper.readTree(escalatorResponse)
                .path("data").path("publicId").asText();

        String escalatorToken = login("escalator1", "EscalatorPass-2026");
        mvc.perform(post("/api/platform/v1/admins/{publicId}/roles", escalatorId)
                        .header("Authorization", bearer(escalatorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCodes":["PLATFORM_SUPER_ADMIN"]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        String adminsResponse = mvc.perform(get("/api/platform/v1/admins")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String superAdminId = findPublicIdByUsername(
                objectMapper.readTree(adminsResponse).path("data").path("items"),
                "admin"
        );
        mvc.perform(patch("/api/platform/v1/admins/{publicId}/status", superAdminId)
                        .header("Authorization", bearer(escalatorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }
    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/api/platform/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("username", username, "password", password)
                        )))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String findPublicIdByUsername(JsonNode admins, String username) {
        for (JsonNode admin : admins) {
            if (username.equals(admin.path("username").asText())) {
                return admin.path("publicId").asText();
            }
        }
        throw new AssertionError("admin not found: " + username);
    }
    private static String findPublicIdByCode(JsonNode roles, String code) {
        for (JsonNode role : roles) {
            if (code.equals(role.path("code").asText())) {
                return role.path("publicId").asText();
            }
        }
        throw new AssertionError("role not found: " + code);
    }
}
