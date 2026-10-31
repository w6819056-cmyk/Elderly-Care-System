package com.smartelder.api.tenant.modules;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartelder.api.tenant.auth.TenantStaffAuthService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HqModuleControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private TenantStaffAuthService auth;
    private final Set<String> createdModules = new java.util.HashSet<>();
    private final Set<String> createdStores = new java.util.HashSet<>();

    @AfterEach
    void cleanup() {
        for (String module : createdModules) {
            jdbc.sql("DELETE FROM core_store_module_assignment WHERE module_code=:module").param("module", module).update();
            jdbc.sql("DELETE FROM core_enterprise_module WHERE module_code=:module").param("module", module).update();
            jdbc.sql("DELETE FROM lic_module_entitlement WHERE module_code=:module").param("module", module).update();
        }
        for (String store : createdStores)
            jdbc.sql("DELETE FROM core_store WHERE public_id=:store").param("store", store).update();
        createdModules.clear(); createdStores.clear();
    }

    @Test
    void hqCanEnableAssignInspectAndRevokeLicensedModule() throws Exception {
        String module = "testmod" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String store = UUID.randomUUID().toString();
        createdModules.add(module); createdStores.add(store);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO core_store(public_id,store_no,name,status,created_at,updated_at) VALUES(:id,:no,'模块测试门店','ACTIVE',:n,:n)")
                .param("id", store).param("no", "S-" + store).param("n", now).update();
        jdbc.sql("INSERT INTO lic_module_entitlement(module_code,license_type,status,starts_at,perpetual,store_quota,license_version,updated_at) "
                + "VALUES(:module,'FORMAL','ACTIVE',:n,TRUE,1,1,:n)").param("module", module).param("n", now).update();
        String token = createHqToken(Set.of("core.module.view", "core.module.manage", "core.module.assignment.manage"));

        mvc.perform(put("/api/tenant/v1/hq/modules/{module}/activation", module)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tenant/v1/hq/modules/{module}/assignments", module)
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", "assign-" + store)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storePublicId\":\"" + store + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get("/api/tenant/v1/hq/modules").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.moduleCode=='" + module + "')].assignedStores").value(1));
        mvc.perform(delete("/api/tenant/v1/hq/modules/{module}/assignments/{store}", module, store)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenant/v1/hq/modules/{module}/assignments", module)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("REVOKED"));
    }

    @Test
    void moduleManagementRequiresSpecificPermission() throws Exception {
        String token = createHqToken(Set.of("core.module.view"));
        mvc.perform(put("/api/tenant/v1/hq/modules/membership/activation")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private String createHqToken(Set<String> permissions) {
        String publicId = UUID.randomUUID().toString();
        String username = "mod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO iam_staff(public_id,username,password_hash,display_name,staff_type,status,created_at,updated_at) "
                + "VALUES(:id,:username,:hash,'总部测试员','HQ','ACTIVE',:now,:now)")
                .param("id", publicId).param("username", username).param("hash", encoder.encode("pw"))
                .param("now", now).update();
        long staffId = jdbc.sql("SELECT id FROM iam_staff WHERE public_id=:id").param("id", publicId).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_scope_config(staff_id,scope_type,version,updated_at) VALUES(:id,'ALL',0,:now)")
                .param("id", staffId).param("now", now).update();
        String rolePublic = UUID.randomUUID().toString();
        String roleCode = "MOD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("INSERT INTO iam_role(public_id,role_code,name,role_domain,protected,status,created_at,updated_at) "
                + "VALUES(:id,:code,'模块测试角色','HQ',FALSE,'ACTIVE',:now,:now)")
                .param("id", rolePublic).param("code", roleCode).param("now", now).update();
        long roleId = jdbc.sql("SELECT id FROM iam_role WHERE public_id=:id").param("id", rolePublic).query(Long.class).single();
        jdbc.sql("INSERT INTO iam_staff_role(staff_id,role_id) VALUES(:staff,:role)").param("staff", staffId).param("role", roleId).update();
        for (String permission : permissions) {
            long permissionId = jdbc.sql("SELECT id FROM iam_permission_definition WHERE permission_code=:code")
                    .param("code", permission).query(Long.class).single();
            jdbc.sql("INSERT INTO iam_role_permission(role_id,permission_definition_id,effect) VALUES(:role,:permission,'ALLOW')")
                    .param("role", roleId).param("permission", permissionId).update();
        }
        return auth.login(username, "pw").accessToken();
    }
}
