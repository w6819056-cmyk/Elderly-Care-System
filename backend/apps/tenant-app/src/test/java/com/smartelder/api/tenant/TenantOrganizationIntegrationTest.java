package com.smartelder.api.tenant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.auth.TenantStaffAuthService;
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
class TenantOrganizationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantStaffAuthService authService;
    @Autowired private ObjectMapper mapper;

    @Test
    void hqManagerCanCreateActivateSuspendAndReadStore() throws Exception {
        String token = createHqToken(Set.of("core.store.manage", "core.store.view"));
        String storeNo = "S-" + UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/tenant/v1/hq/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeNo + "\",\"name\":\"测试门店\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        JsonNode response = mapper.readTree(created.getResponse().getContentAsString());
        String publicId = response.path("data").path("publicId").asText();

        mvc.perform(patch("/api/tenant/v1/hq/stores/{id}/status", publicId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operational").value(true));
        mvc.perform(patch("/api/tenant/v1/hq/stores/{id}/status", publicId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operational").value(false));
        mvc.perform(get("/api/tenant/v1/hq/stores").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].publicId").value(org.hamcrest.Matchers.hasItem(publicId)))
                .andExpect(jsonPath("$.data[*].status").value(org.hamcrest.Matchers.hasItem("SUSPENDED")));
    }

    @Test
    void staffWithoutStoreManagePermissionGets403Envelope() throws Exception {
        String token = createHqToken(Set.of("core.store.view"));

        mvc.perform(post("/api/tenant/v1/hq/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"S-DENY\",\"name\":\"不可创建\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void invalidStoreTransitionReturnsConflictEnvelope() throws Exception {
        String token = createHqToken(Set.of("core.store.manage"));
        String storeNo = "S-" + UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/tenant/v1/hq/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeNo + "\",\"name\":\"测试门店\"}"))
                .andExpect(status().isOk()).andReturn();
        String publicId = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("publicId").asText();

        mvc.perform(patch("/api/tenant/v1/hq/stores/{id}/status", publicId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    private String createHqToken(Set<String> permissions) {
        String publicId = UUID.randomUUID().toString();
        String username = "hq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_staff (public_id, username, password_hash, display_name, staff_type, status, "
                        + "version, created_at, updated_at) VALUES (:publicId, :username, :hash, '总部员工', 'HQ', "
                        + "'ACTIVE', 0, :now, :now)")
                .param("publicId", publicId).param("username", username)
                .param("hash", passwordEncoder.encode("test-password")).param("now", now).update();
        long createdStaffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_scope_config (staff_id, scope_type, version, updated_at) "
                        + "VALUES (:staffId, 'ALL', 0, :now)")
                .param("staffId", createdStaffId).param("now", now).update();
        String rolePublicId = UUID.randomUUID().toString();
        String roleCode = "TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.sql("INSERT INTO iam_role (public_id, role_code, name, role_domain, protected, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, :code, '测试权限', 'HQ', FALSE, 'ACTIVE', 0, :now, :now)")
                .param("publicId", rolePublicId).param("code", roleCode).param("now", now).update();
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).single();
        long roleId = jdbc.sql("SELECT id FROM iam_role WHERE public_id = :publicId")
                .param("publicId", rolePublicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_role (staff_id, role_id) VALUES (:staffId, :roleId)")
                .param("staffId", staffId).param("roleId", roleId).update();
        for (String permission : permissions) {
            long permissionId = jdbc.sql("SELECT id FROM iam_permission_definition WHERE permission_code = :code")
                    .param("code", permission).query(Long.class).single();
            jdbc.sql("INSERT INTO iam_role_permission (role_id, permission_definition_id, effect) "
                            + "VALUES (:roleId, :permissionId, 'ALLOW')")
                    .param("roleId", roleId).param("permissionId", permissionId).update();
        }
        return authService.login(username, "test-password").accessToken();
    }
}
