package com.smartelder.platform.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.PlatformApplication;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class ModuleCatalogIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void managesCatalogOrderingVersionsDependenciesAndPrices() throws Exception {
        String token = login();
        String dependencyCode = code("base");
        String mainCode = code("main");
        createModule(token, dependencyCode, "基础模块", 300, false);
        createModule(token, mainCode, "机构康养", 100, true);
        createVersion(token, dependencyCode, "1.0.0", "1.0.0");
        createVersion(token, mainCode, "1.0.0", "1.0.0");
        createVersion(token, mainCode, "1.2.0", "1.1.0");

        mvc.perform(put("/api/platform/v1/modules/{code}/dependencies", mainCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dependencies":[
                                  {"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}
                                ]}
                                """.formatted(dependencyCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].moduleCode").value(dependencyCode));

        mvc.perform(put("/api/platform/v1/modules/{code}/prices", mainCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"1980.00",
                                   "currency":"CNY","storeQuota":20,"trialDays":7},
                                  {"billingMode":"PERPETUAL","durationDays":0,"amount":"4980.00",
                                   "currency":"CNY","storeQuota":20}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        changeStatus(token, dependencyCode, "ON_SALE").andExpect(status().isOk()).andExpect(jsonPath("$.data.saleStatus").value("ON_SALE"));
        changeStatus(token, mainCode, "ON_SALE").andExpect(status().isOk()).andExpect(jsonPath("$.data.saleStatus").value("ON_SALE"));

        String listResponse = mvc.perform(get("/api/platform/v1/modules")
                        .header("Authorization", bearer(token))
                        .queryParam("status", "ON_SALE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(listResponse).path("data").path("items");
        int mainIndex = indexOf(items, mainCode);
        int dependencyIndex = indexOf(items, dependencyCode);
        assertThat(mainIndex).isGreaterThanOrEqualTo(0);
        assertThat(dependencyIndex).isGreaterThan(mainIndex);

        mvc.perform(get("/api/platform/v1/modules/{code}", mainCode)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moduleCode").value(mainCode))
                .andExpect(jsonPath("$.data.name").value("机构康养"))
                .andExpect(jsonPath("$.data.menuOrder").value(100))
                .andExpect(jsonPath("$.data.version").value("1.2.0"))
                .andExpect(jsonPath("$.data.dependencies[0].moduleCode").value(dependencyCode))
                .andExpect(jsonPath("$.data.dependencies[0].type").value("REQUIRED"))
                .andExpect(jsonPath("$.data.prices[0].amount").value("1980.00"))
                .andExpect(jsonPath("$.data.permissionCodes[0]").value("module." + mainCode + ".access"));
    }

    @Test
    void rejectsDuplicateCodeAndMenuOrderConflict() throws Exception {
        String token = login();
        String firstCode = code("first");
        String secondCode = code("second");
        String draftCode = code("draft");
        createModule(token, firstCode, "第一个模块", 500, false);
        createModule(token, secondCode, "第二个模块", 501, false);
        createVersion(token, firstCode, "1.0.0", "1.0.0");
        createVersion(token, secondCode, "1.0.0", "1.0.0");
        changeStatus(token, firstCode, "ON_SALE").andExpect(status().isOk()).andExpect(jsonPath("$.data.saleStatus").value("ON_SALE"));
        changeStatus(token, secondCode, "ON_SALE").andExpect(status().isOk()).andExpect(jsonPath("$.data.saleStatus").value("ON_SALE"));

        mvc.perform(post("/api/platform/v1/modules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moduleBody(firstCode, "重复模块", 502, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_CODE_CONFLICT"));

        createModule(token, draftCode, "待上架模块", 500, false);
        createVersion(token, draftCode, "1.0.0", "1.0.0");
        changeStatus(token, draftCode, "ON_SALE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_ORDER_CONFLICT"));
    }

    @Test
    void rejectsSelfUnknownAndCyclicDependencies() throws Exception {
        String token = login();
        String codeA = code("depa");
        String codeB = code("depb");
        String codeC = code("depc");
        createModule(token, codeA, "依赖 A", 600, false);
        createModule(token, codeB, "依赖 B", 601, false);
        createModule(token, codeC, "依赖 C", 602, false);

        replaceDependencies(token, codeA, """
                [{"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}]
                """.formatted(codeA))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_INVALID"));

        replaceDependencies(token, codeA, """
                [{"moduleCode":"missing-%s","type":"REQUIRED","minimumVersion":"1.0.0"}]
                """.formatted(UUID.randomUUID().toString().substring(0, 6)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_INVALID"));

        replaceDependencies(token, codeA, """
                [{"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}]
                """.formatted(codeB))
                .andExpect(status().isOk());
        replaceDependencies(token, codeB, """
                [{"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}]
                """.formatted(codeC))
                .andExpect(status().isOk());
        replaceDependencies(token, codeC, """
                [{"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}]
                """.formatted(codeA))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_CYCLE"));

        mvc.perform(get("/api/platform/v1/modules/{code}", codeC)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dependencies.length()").value(0));
    }

    @Test
    void validatesBillingModesPeriodsAndTrialPolicy() throws Exception {
        String token = login();
        String priceCode = code("price");
        createModule(token, priceCode, "价格校验", 700, false);

        mvc.perform(put("/api/platform/v1/modules/{code}/prices", priceCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"100.00",
                                   "currency":"CNY","storeQuota":10},
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"90.00",
                                   "currency":"CNY","storeQuota":10}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_PRICE_INVALID"));

        mvc.perform(put("/api/platform/v1/modules/{code}/prices", priceCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":30,"amount":"100.00",
                                   "currency":"CNY","storeQuota":10,"trialDays":0}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_PRICE_INVALID"));

        mvc.perform(put("/api/platform/v1/modules/{code}/prices", priceCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERPETUAL","durationDays":1,"amount":"100.00",
                                   "currency":"CNY","storeQuota":10}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_PRICE_INVALID"));

        String trialCode = code("trial");
        createModule(token, trialCode, "试用模块", 701, true);
        mvc.perform(put("/api/platform/v1/modules/{code}/prices", trialCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"100.00",
                                   "currency":"CNY","storeQuota":10,"trialDays":7}
                                ]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void validatesStatusVersionsAndFailedReplacementAtomicity() throws Exception {
        String token = login();
        String moduleCode = code("atomic");
        String dependencyCode = code("replacement");
        createModule(token, moduleCode, "原子性校验", 800, false);
        createModule(token, dependencyCode, "替换依赖", 801, false);

        changeStatus(token, moduleCode, "OFF_SALE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_STATE_CONFLICT"));

        mvc.perform(post("/api/platform/v1/modules/{code}/versions", moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0","minCoreVersion":"1.0.0"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_VERSION_INVALID"));

        createVersion(token, moduleCode, "1.9.0", "1.0.0");
        createVersion(token, moduleCode, "1.10.0", "1.0.0");
        mvc.perform(get("/api/platform/v1/modules/{code}", moduleCode)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.10.0"));

        replaceDependencies(token, moduleCode, """
                [{"moduleCode":"%s","type":"OPTIONAL","minimumVersion":"1.0.0"}]
                """.formatted(dependencyCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].moduleCode").value(dependencyCode));

        replaceDependencies(token, moduleCode, """
                [{"moduleCode":"%s","type":"OPTIONAL","minimumVersion":"1.0.0"},
                 {"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}]
                """.formatted(dependencyCode, dependencyCode))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_INVALID"));

        mvc.perform(get("/api/platform/v1/modules/{code}", moduleCode)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dependencies.length()").value(1))
                .andExpect(jsonPath("$.data.dependencies[0].moduleCode").value(dependencyCode))
                .andExpect(jsonPath("$.data.dependencies[0].type").value("OPTIONAL"));

        mvc.perform(put("/api/platform/v1/modules/{code}/prices", moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"88.00",
                                   "currency":"CNY","storeQuota":10}
                                ]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(put("/api/platform/v1/modules/{code}/prices", moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"100.00",
                                   "currency":"CNY","storeQuota":10},
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"90.00",
                                   "currency":"CNY","storeQuota":10}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_PRICE_INVALID"));

        mvc.perform(get("/api/platform/v1/modules/{code}", moduleCode)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prices.length()").value(1))
                .andExpect(jsonPath("$.data.prices[0].amount").value("88.00"));
    }
    @Test
    void rejectsUnderscoreModuleCodeAndAcceptsHyphenatedCode() throws Exception {
        String token = login();

        mvc.perform(post("/api/platform/v1/modules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moduleBody(
                                "invalid_module_" + UUID.randomUUID().toString().substring(0, 8),
                                "非法模块编码",
                                901,
                                false
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        createModule(token, code("valid-hyphen"), "合法连字符模块", 902, false);
    }

    private void createModule(
            String token,
            String code,
            String name,
            int menuOrder,
            boolean trialEnabled
    ) throws Exception {
        mvc.perform(post("/api/platform/v1/modules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moduleBody(code, name, menuOrder, trialEnabled)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.moduleCode").value(code))
                .andExpect(jsonPath("$.data.saleStatus").value("DRAFT"));
    }

    private void createVersion(
            String token,
            String code,
            String version,
            String minCoreVersion
    ) throws Exception {
        mvc.perform(post("/api/platform/v1/modules/{code}/versions", code)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"%s","minCoreVersion":"%s"}
                                """.formatted(version, minCoreVersion)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(
            String token,
            String code,
            String statusValue
    ) throws Exception {
        return mvc.perform(put("/api/platform/v1/modules/{code}/status", code)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"%s"}
                        """.formatted(statusValue)));
    }

    private org.springframework.test.web.servlet.ResultActions replaceDependencies(
            String token,
            String code,
            String dependenciesJson
    ) throws Exception {
        return mvc.perform(put("/api/platform/v1/modules/{code}/dependencies", code)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dependencies\":" + dependenciesJson + "}"));
    }

    private String moduleBody(String code, String name, int menuOrder, boolean trialEnabled) {
        return """
                {
                  "code":"%s",
                  "name":"%s",
                  "description":"模块目录集成测试",
                  "menuOrder":%d,
                  "trialEnabled":%s,
                  "dependencies":[],
                  "prices":[]
                }
                """.formatted(code, name, menuOrder, trialEnabled);
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/platform/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "ChangeMe-Platform-2026"
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private static int indexOf(JsonNode items, String code) {
        for (int index = 0; index < items.size(); index++) {
            if (code.equals(items.get(index).path("moduleCode").asText())) {
                return index;
            }
        }
        return -1;
    }

    private static String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
