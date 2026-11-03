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
    void customerManagementRequiresItsPermissionAndReturnsOnlyPublicAccountFields() throws Exception {
        String token = createHqManagerToken();
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/customers");
        mvc.perform(get.header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        long staff = authService.resolveToken(token).accessContext().staffId();
        jdbc.sql("INSERT INTO iam_role_permission(role_id,permission_definition_id,effect) SELECT sr.role_id,d.id,'ALLOW' FROM iam_staff_role sr CROSS JOIN iam_permission_definition d WHERE sr.staff_id=:id AND d.permission_code='core.customer.view'").param("id",staff).update();
        String username="managed_"+UUID.randomUUID().toString().substring(0,8);
        var registered=mvc.perform(post("/api/public/customer/register").remoteAddress("10.30.5.1").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("username",username,"password","Customer-Test-2026!","displayName","客户管理测试"))))
                .andExpect(status().isOk()).andReturn();
        String id=mapper.readTree(registered.getResponse().getContentAsString()).path("data").path("profile").path("publicId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/customers").param("q",username).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value(username))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/customers/"+id).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.customer.publicId").value(id))
                .andExpect(jsonPath("$.data.bookings").isEmpty());
    }

    @Test
    void customerBookingsSurviveLoginAndStayPrivate() throws Exception {
        String no=jdbc.sql("SELECT store_no FROM core_store WHERE public_id=:id").param("id",insertStore("ACTIVE")).query(String.class).single();
        String username="customer_"+UUID.randomUUID().toString().substring(0,8);
        String registration=mapper.writeValueAsString(java.util.Map.of("username",username,"password","Customer-Test-2026!","displayName","预约用户"));
        var registered=mvc.perform(post("/api/public/customer/register").remoteAddress("10.30.1.1").contentType(MediaType.APPLICATION_JSON).content(registration)).andExpect(status().isOk()).andReturn();
        String token=mapper.readTree(registered.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        String booking=mapper.writeValueAsString(java.util.Map.of("visitorName","本人预约","phone","13800001111","visitType","VISIT","preferredDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).toString()));
        mvc.perform(post("/api/public/customer/stores/"+no+"/bookings").header("X-Customer-Token",token).contentType(MediaType.APPLICATION_JSON).content(booking)).andExpect(status().isOk());
        var login=mvc.perform(post("/api/public/customer/login").remoteAddress("10.30.1.1").contentType(MediaType.APPLICATION_JSON).content(registration)).andExpect(status().isOk()).andReturn();
        String second=mapper.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/customer/bookings").header("X-Customer-Token",second)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].visitorName").value("本人预约"));
        var other=mvc.perform(post("/api/public/customer/register").remoteAddress("10.30.1.2").contentType(MediaType.APPLICATION_JSON).content(registration.replace(username,username+"_other"))).andExpect(status().isOk()).andReturn();
        String otherToken=mapper.readTree(other.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/customer/bookings").header("X-Customer-Token",otherToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/customer/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/customer/logout").header("X-Customer-Token",second)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/customer/me").header("X-Customer-Token",second)).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test
    void publicSitePublishesOnlyReviewedSnapshotAndRejectsStaleReview() throws Exception {
        String token=createHqManagerToken();long manager=authService.resolveToken(token).staffId();
        jdbc.sql("INSERT INTO iam_role_permission(role_id,permission_definition_id,effect) SELECT sr.role_id,d.id,'ALLOW' FROM iam_staff_role sr CROSS JOIN iam_permission_definition d WHERE sr.staff_id=:id AND d.permission_code IN ('core.site.view','core.site.manage','core.site.review')").param("id",manager).update();
        String id=insertStore("ACTIVE");String no=jdbc.sql("SELECT store_no FROM core_store WHERE public_id=:id").param("id",id).query(String.class).single();
        String first=mapper.writeValueAsString(java.util.Map.of("title","首个公开版本","introduction","仅审核通过后公开","heroImage","/images/enterprise-residence.png","gallery",java.util.List.of()));
        mvc.perform(put("/api/tenant/v1/site-content/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(first)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/site/stores/"+no)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content").doesNotExist());
        mvc.perform(post("/api/tenant/v1/site-content/"+id+"/submit").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        var editor=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/site-content/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        String hash=mapper.readTree(editor.getResponse().getContentAsString()).path("data").path("reviewToken").asText();
        mvc.perform(put("/api/tenant/v1/site-content/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(first.replace("首个公开版本","后续未送审草稿"))).andExpect(status().isOk());
        mvc.perform(post("/api/tenant/v1/site-content/"+id+"/approve").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("reviewToken",hash,"note","通过")))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/site/stores/"+no)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("首个公开版本"));
        mvc.perform(post("/api/tenant/v1/site-content/"+id+"/submit").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/tenant/v1/site-content/"+id+"/approve").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("reviewToken",hash)))).andExpect(status().isConflict());
    }

    @Test
    void visitIntentCanBeConfirmedAndCompletedWithPermissions() throws Exception {
        String token=createHqManagerToken();String store=insertStore("ACTIVE");String no=jdbc.sql("SELECT store_no FROM core_store WHERE public_id=:id").param("id",store).query(String.class).single();
        var result=mvc.perform(post("/api/public/store-portals/"+no+"/visit-bookings").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("visitorName","到访测试","phone","13800001111","visitType","VISIT","preferredDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).toString(),"remark","上午联系")))).andExpect(status().isOk()).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).path("data").path("bookingNo").asText();
        assertThat(jdbc.sql("SELECT status FROM core_visit_booking WHERE public_id=:id").param("id",id).query(String.class).single()).isEqualTo("PENDING");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/tenant/v1/visit-bookings/"+id+"/status").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isForbidden());
        long manager=authService.resolveToken(token).staffId();
        jdbc.sql("INSERT INTO iam_role_permission(role_id,permission_definition_id,effect) SELECT sr.role_id,d.id,'ALLOW' FROM iam_staff_role sr CROSS JOIN iam_permission_definition d WHERE sr.staff_id=:id AND d.permission_code IN ('core.visit.view','core.visit.manage')").param("id",manager).update();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/visit-bookings").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        for(String state:java.util.List.of("CONFIRMED","COMPLETED"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/tenant/v1/visit-bookings/"+id+"/status").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\""+state+"\"}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/tenant/v1/visit-bookings/"+id+"/status").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isConflict());
    }

    @Test
    void portalApplicationRequiresApprovalAndStoreLoginIsBound() throws Exception {
        String token=createHqManagerToken();String storeId=insertStore("ACTIVE");String other=insertStore("ACTIVE");
        String storeNo=jdbc.sql("SELECT store_no FROM core_store WHERE public_id=:id").param("id",storeId).query(String.class).single();
        String otherNo=jdbc.sql("SELECT store_no FROM core_store WHERE public_id=:id").param("id",other).query(String.class).single();
        String username="portal_"+UUID.randomUUID().toString().substring(0,8),role="PORTAL_"+UUID.randomUUID().toString().replace("-", "").substring(0,8).toUpperCase();
        mvc.perform(post("/api/tenant/v1/hq/roles").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(java.util.Map.of("roleCode",role,"name","门店员工","roleDomain","STORE","permissionGroupCodes",java.util.List.of(),"permissionEffects",java.util.Map.of())))).andExpect(status().isOk());
        var result=mvc.perform(post("/api/public/store-portals/"+storeNo+"/applications").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(java.util.Map.of("username",username,"password","portal-password-123","displayName","申请员工","phone","13"+System.nanoTime()%1000000000,"email","portal@example.com")))).andExpect(status().isOk()).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).path("data").path("applicationNo").asText();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM iam_staff WHERE username=:name").param("name",username).query(Long.class).single()).isZero();
        String review=mapper.writeValueAsString(java.util.Map.of("decision","APPROVE","roleCodes",java.util.List.of(role),"note","同意入职"));
        mvc.perform(post("/api/tenant/v1/hq/staff-applications/"+id+"/review").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isOk());
        mvc.perform(post("/api/tenant/v1/hq/staff-applications/"+id+"/review").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isConflict());
        String login=mapper.writeValueAsString(java.util.Map.of("account",username,"password","portal-password-123"));
        mvc.perform(post("/api/public/store-portals/"+storeNo+"/login").contentType(MediaType.APPLICATION_JSON).content(login)).andExpect(status().isOk()).andExpect(jsonPath("$.data.principal.staffType").value("STORE"));
        mvc.perform(post("/api/public/store-portals/"+otherNo+"/login").contentType(MediaType.APPLICATION_JSON).content(login)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT password_hash FROM iam_staff_application WHERE public_id=:id").param("id",id).query((rs,n)->rs.getString(1)).single()).isNull();
    }

    @Test
    void staffAccessDetailsReflectRolesAndExplicitScope() throws Exception {
        String token=createHqManagerToken();
        String username="access_"+UUID.randomUUID().toString().substring(0,8);
        var created=mvc.perform(post("/api/tenant/v1/hq/staff").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("username",username,"password","access-password-123","displayName","区域经理","staffType","HQ","roleCodes",java.util.List.of()))))
                .andExpect(status().isOk()).andReturn();
        String id=mapper.readTree(created.getResponse().getContentAsString()).path("data").path("publicId").asText();
        String store=insertStore("ACTIVE");
        mvc.perform(put("/api/tenant/v1/hq/staff/"+id+"/data-scope").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("scopeType","STORES","storePublicIds",java.util.List.of(store)))))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/hq/staff/"+id+"/access").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scopeType").value("STORES"))
                .andExpect(jsonPath("$.data.storePublicIds[0]").value(store))
                .andExpect(jsonPath("$.data.protectedOwner").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/hq/staff/"+id+"/access"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void groupAndRoleDetailsRoundTripPermissionSelections() throws Exception {
        String token=createHqManagerToken();
        String code="DETAIL_"+UUID.randomUUID().toString().replace("-", "").substring(0,8).toUpperCase();
        var group=mvc.perform(post("/api/tenant/v1/hq/permission-groups").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("groupCode",code,"name","详情权限组","permissionCodes",java.util.List.of("core.store.view")))))
                .andExpect(status().isOk()).andReturn();
        String groupId=mapper.readTree(group.getResponse().getContentAsString()).path("data").path("publicId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/hq/permission-groups/"+groupId).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissionCodes[0]").value("core.store.view"));
        var role=mvc.perform(post("/api/tenant/v1/hq/roles").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("roleCode",code,"name","详情角色","roleDomain","HQ","permissionGroupCodes",java.util.List.of(code),"permissionEffects",java.util.Map.of("core.store.manage","DENY")))))
                .andExpect(status().isOk()).andReturn();
        String roleId=mapper.readTree(role.getResponse().getContentAsString()).path("data").path("publicId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/hq/roles/"+roleId).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissionGroupCodes[0]").value(code))
                .andExpect(jsonPath("$.data.permissionEffects['core.store.manage']").value("DENY"));
    }

    @Test
    void contactFieldsPersistAndInvalidAvatarCreatesNoStaff() throws Exception {
        String token = createHqManagerToken();
        String username = "profile_" + UUID.randomUUID().toString().substring(0, 8);
        String body = mapper.writeValueAsString(java.util.Map.of("username",username,"password","profile-password-123",
                "displayName","资料测试员工","staffType","HQ","roleCodes",java.util.List.of(),"phone","13800001234","email","profile@example.com"));
        mvc.perform(post("/api/tenant/v1/hq/staff").header("Authorization","Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("13800001234"))
                .andExpect(jsonPath("$.data.email").value("profile@example.com"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tenant/v1/hq/staff")
                .header("Authorization","Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.username == '"+username+"')].email").value(org.hamcrest.Matchers.hasItem("profile@example.com")));
        String invalid = body.replace(username,username+"_invalid").replace("{","{\"avatarDataUrl\":\"data:image/png;base64,SGVsbG8=\",");
        mvc.perform(post("/api/tenant/v1/hq/staff").header("Authorization","Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().is4xxClientError());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM iam_staff WHERE username=:name").param("name",username+"_invalid").query(Long.class).single()).isZero();
    }

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
