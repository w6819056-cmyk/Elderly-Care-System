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
    private boolean createdLicenseSnapshot;

    @AfterEach
    void cleanup() {
        for (String module : createdModules) {
            jdbc.sql("DELETE FROM core_store_module_assignment WHERE module_code=:module").param("module", module).update();
            jdbc.sql("DELETE FROM core_enterprise_module WHERE module_code=:module").param("module", module).update();
            jdbc.sql("DELETE FROM lic_module_entitlement WHERE module_code=:module").param("module", module).update();
        }
        for (String store : createdStores)
            jdbc.sql("DELETE FROM core_store WHERE public_id=:store").param("store", store).update();
        if (createdLicenseSnapshot) jdbc.sql("DELETE FROM lic_license_snapshot WHERE id=1").update();
        createdModules.clear(); createdStores.clear();
        createdLicenseSnapshot = false;
    }

    @Test
    void hqCanEnableAssignInspectAndRevokeLicensedModule() throws Exception {
        String module = "membership";
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
    void authorizedButUninstalledModuleRemainsManageableAndIsNotExposedAsBusinessMenu() throws Exception {
        String module = "community";
        String store = UUID.randomUUID().toString();
        String assignmentId = UUID.randomUUID().toString();
        String idempotencyKey = "install-state-" + UUID.randomUUID();
        createdModules.add(module);
        createdStores.add(store);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO core_store(public_id,store_no,name,status,created_at,updated_at) VALUES(:id,:no,'未安装模块测试门店','ACTIVE',:n,:n)")
                .param("id", store).param("no", "S-" + store).param("n", now).update();
        jdbc.sql("INSERT INTO lic_module_entitlement(module_code,license_type,status,starts_at,perpetual,store_quota,license_version,updated_at) "
                + "VALUES(:module,'FORMAL','ACTIVE',:n,TRUE,1,1,:n)").param("module", module).param("n", now).update();
        jdbc.sql("INSERT INTO core_enterprise_module(module_code,enabled,version,updated_at) VALUES(:module,TRUE,0,:n)")
                .param("module", module).param("n", now).update();
        long staffId = createHqStaff(Set.of("core.module.view", "core.module.manage", "community:service:read"));
        jdbc.sql("INSERT INTO core_store_module_assignment(public_id,module_code,store_id,status,idempotency_key,assigned_by_staff_id,assigned_at) "
                + "SELECT :publicId,:module,id,'ACTIVE',:key,:staff,:now FROM core_store WHERE public_id=:store")
                .param("publicId", assignmentId).param("module", module).param("key", idempotencyKey)
                .param("staff", staffId).param("now", now).param("store", store).update();
        ensureActiveLicenseSnapshot(now);

        mvc.perform(get("/api/tenant/v1/hq/modules").header("Authorization", "Bearer " + auth.login(usernameFor(staffId), "pw").accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleCode=='community')].installed").value(false));

        String token = auth.login(usernameFor(staffId), "pw").accessToken();
        mvc.perform(get("/api/tenant/v1/modules/menus").param("storeId", Long.toString(storeId(store)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleCode=='community')]").doesNotExist());
        String catalogPermissionName = jdbc.sql("SELECT name FROM iam_permission_definition WHERE permission_code='community:service:read'")
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(catalogPermissionName).isEqualTo("社区居家模块权限（待安装）");
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
        long staffId = createHqStaff(permissions);
        return auth.login(usernameFor(staffId), "pw").accessToken();
    }

    private long createHqStaff(Set<String> permissions) {
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
        return staffId;
    }

    private String usernameFor(long staffId) {
        return jdbc.sql("SELECT username FROM iam_staff WHERE id=:id").param("id", staffId).query(String.class).single();
    }

    private long storeId(String publicId) {
        return jdbc.sql("SELECT id FROM core_store WHERE public_id=:id").param("id", publicId).query(Long.class).single();
    }

    private void ensureActiveLicenseSnapshot(Timestamp now) {
        long count = jdbc.sql("SELECT COUNT(*) FROM lic_license_snapshot WHERE id=1").query(Long.class).single();
        if (count > 0) return;
        jdbc.sql("INSERT INTO lic_license_snapshot(id,org_no,deployment_instance_id,instance_fingerprint,license_id,schema_version,license_version,raw_license_json,canonical_payload,sha256,signature_algorithm,key_id,signature_value,issued_at,not_before,offline_grace_days,heartbeat_interval_hours,last_platform_time,last_successful_sync_at,platform_instance_status,sync_status) "
                        + "VALUES(1,'ORG','instance','fingerprint','license',2,1,'{}','{}',:sha,'Ed25519','test','test',:now,:now,7,6,:now,:now,'ACTIVE','OK')")
                .param("sha", "0".repeat(64)).param("now", now).update();
        createdLicenseSnapshot = true;
    }
}
