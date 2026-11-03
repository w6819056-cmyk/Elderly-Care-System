package com.smartelder.platform.api.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.PlatformApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class OrderPurchaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void productCardsReadEditedInformationAndCouponIsReservedOnlyByAnOrder() throws Exception {
        String token=login();String code=uniqueModuleCode("product");
        createOnSaleModule(token,code,"技术模块名","300.00",10);
        String productBody=objectMapper.writeValueAsString(Map.of("moduleCode",code,"name","会员年卡商品","description","完整商品介绍","billingMode","PERIOD","durationDays",365,"amount",280,"storeQuota",10));
        mvc.perform(post("/api/platform/v1/commerce/products").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON).content(productBody)).andExpect(status().isOk());
        long productId=jdbcClient.sql("SELECT id FROM pf_sale_product WHERE name='会员年卡商品'").query(Long.class).single();
        mvc.perform(post("/api/platform/v1/commerce/cards").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("productId",productId,"imageUrl","/images/membership-cover.svg","sortOrder",3,"enabled",true)))).andExpect(status().isOk());
        mvc.perform(put("/api/platform/v1/commerce/products/{id}",productId).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(productBody.replace("会员年卡商品","会员年卡商品改名"))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/v1/commerce/cards").header("Authorization",bearer(token))).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.name == '会员年卡商品改名')]").exists());
        MvcResult coupon=mvc.perform(post("/api/platform/v1/commerce/coupons").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name","八折券","type","DISCOUNT","value",80,"minimumAmount",0,"expiresAt",java.time.Instant.now().plusSeconds(3600).toString())))).andExpect(status().isOk()).andReturn();
        String couponCode=objectMapper.readTree(coupon.getResponse().getContentAsString()).path("data").path("code").asText();
        mvc.perform(post("/api/platform/v1/commerce/quote").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("productId",productId,"couponCode",couponCode)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.payableAmount").value(224.00));
        assertThat(jdbcClient.sql("SELECT status FROM pf_sale_coupon WHERE code=:code").param("code",couponCode).query(String.class).single()).isEqualTo("AVAILABLE");
        String enterprise=createEnterprise(token);
        var command=objectMapper.readTree(directBody(enterprise,code,10));((com.fasterxml.jackson.databind.node.ObjectNode)command).put("couponCode",couponCode);
        MvcResult created=mvc.perform(post("/api/platform/v1/orders").header("Authorization",bearer(token)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(command.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.payableAmount").value(224.00)).andReturn();
        String order=objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("publicId").asText();
        assertThat(jdbcClient.sql("SELECT status FROM pf_sale_coupon WHERE code=:code").param("code",couponCode).query(String.class).single()).isEqualTo("RESERVED");
        mvc.perform(post("/api/platform/v1/orders").header("Authorization",bearer(token)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(command.toString())).andExpect(status().isConflict());
        mvc.perform(post("/api/platform/v1/orders/{id}/cancel",order).header("Authorization",bearer(token))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(jdbcClient.sql("SELECT status FROM pf_sale_coupon WHERE code=:code").param("code",couponCode).query(String.class).single()).isEqualTo("AVAILABLE");
        MvcResult second=mvc.perform(post("/api/platform/v1/orders").header("Authorization",bearer(token)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(command.toString())).andExpect(status().isCreated()).andReturn();
        String secondId=objectMapper.readTree(second.getResponse().getContentAsString()).path("data").path("publicId").asText();
        mvc.perform(post("/api/platform/v1/orders/{id}/pay-simulated",secondId).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("channel","MOCK","channelTransactionNo",UUID.randomUUID().toString())))).andExpect(status().isOk());
        assertThat(jdbcClient.sql("SELECT status FROM pf_sale_coupon WHERE code=:code").param("code",couponCode).query(String.class).single()).isEqualTo("USED");
        mvc.perform(get("/api/platform/v1/commerce/dashboard").header("Authorization",bearer(token))).andExpect(status().isOk()).andExpect(jsonPath("$.data.enterprises").isNumber());
    }

    @Test
    void enterpriseApplicationDoesNotCreateEnterpriseUntilPlatformApproval() throws Exception {
        String token=login();String org="SELF_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        MvcResult submitted=mvc.perform(post("/api/platform/v1/enterprise-applications").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orgNo",org,"name","自助登记公司","contactName","联系人","contactPhone","13800000000"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING")).andReturn();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM pf_enterprise WHERE org_no=:org").param("org",org).query(Long.class).single()).isZero();
        String id=objectMapper.readTree(submitted.getResponse().getContentAsString()).path("data").path("applicationId").asText();
        mvc.perform(post("/api/platform/v1/enterprise-applications/{id}/review",id).contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true,\"reason\":\"资料核验通过\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/platform/v1/enterprise-applications/{id}/review",id).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true,\"reason\":\"资料核验通过\"}")).andExpect(status().isOk());
        assertThat(jdbcClient.sql("SELECT status FROM pf_enterprise WHERE org_no=:org").param("org",org).query(String.class).single()).isEqualTo("ACTIVE");
        mvc.perform(post("/api/platform/v1/enterprise-applications/{id}/review",id).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true,\"reason\":\"重复处理\"}")).andExpect(status().isConflict());
    }

    @Test
    void storefrontEditsPersistAndRejectUnsafeImages() throws Exception {
        String token = login();
        String code = uniqueModuleCode("cover");
        createOnSaleModule(token, code, "会员模块", "300.00", 10);
        mvc.perform(put("/api/platform/v1/storefront/{code}", code)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "会员成长计划", "description", "会员宣传", "imageUrl", "/images/membership-cover.svg", "sortOrder", 2, "enabled", true))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/v1/storefront").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleCode == '%s')].title".formatted(code)).value("会员成长计划"));
        mvc.perform(put("/api/platform/v1/storefront/{code}", code)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "会员", "description", "", "imageUrl", "javascript:alert(1)", "sortOrder", 0, "enabled", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/v1/storefront")).andExpect(status().isUnauthorized());
    }

    @Test
    void managesPlanCatalogWithoutExposingOffSalePlansToEnterprisePurchase() throws Exception {
        String token = login();
        String moduleCode = uniqueModuleCode("catalog");
        createOnSaleModule(token, moduleCode, "套餐目录模块", "80.00", 3);
        String enterpriseId = createEnterprise(token);
        String planCode = uniquePlanCode("catalog_plan");
        mvc.perform(post("/api/platform/v1/plans")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"可管理套餐","description":"套餐目录测试",
                                 "amount":"80.00","minimumAmount":"70.00",
                                 "items":[{"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":3}]}
                                """.formatted(planCode, moduleCode)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/platform/v1/plans/catalog")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.planCode == '%s')]".formatted(planCode)).exists());
        mvc.perform(put("/api/platform/v1/plans/{code}",planCode).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code",planCode,"name","可管理套餐改名","description","组合与售价可修改","amount","75.00","minimumAmount","70.00","items",List.of(Map.of("moduleCode",moduleCode,"billingMode","PERIOD","durationDays",365,"storeQuota",3))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("可管理套餐改名")).andExpect(jsonPath("$.data.amount").value(75.00));


        mvc.perform(put("/api/platform/v1/plans/{code}/status", planCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OFF_SALE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("OFF_SALE"));

        mvc.perform(get("/api/platform/v1/plans/catalog")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.planCode == '%s' && @.saleStatus == 'OFF_SALE')]".formatted(planCode)).exists());
        mvc.perform(get("/api/platform/v1/plans")
                        .header("Authorization", bearer(token))
                        .queryParam("enterpriseId", enterpriseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.planCode == '%s')]".formatted(planCode)).doesNotExist());
    }

    @Test
    void keepsPlanCatalogReadableWhenItsModulePriceIsNoLongerOnSale() throws Exception {
        String token = login();
        String moduleCode = uniqueModuleCode("retired-price");
        createOnSaleModule(token, moduleCode, "历史价格模块", "80.00", 3);
        String planCode = uniquePlanCode("retired_price_plan");
        mvc.perform(post("/api/platform/v1/plans")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"历史套餐","description":"价格停售后仍可查看",
                                 "amount":"80.00","minimumAmount":"70.00",
                                 "items":[{"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":3}]}
                                """.formatted(planCode, moduleCode)))
                .andExpect(status().isCreated());

        jdbcClient.sql("""
                        UPDATE pf_module_price
                        SET sale_status = 'OFF_SALE'
                        WHERE module_id = (SELECT id FROM pf_module_definition WHERE code = :moduleCode)
                        """)
                .param("moduleCode", moduleCode)
                .update();

        MvcResult result = mvc.perform(get("/api/platform/v1/plans/catalog")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode plans = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        JsonNode plan = java.util.stream.StreamSupport.stream(plans.spliterator(), false)
                .filter(item -> planCode.equals(item.path("planCode").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(plan.path("items").get(0).path("standardAmount").isNull()).isTrue();
    }

    @Test
    void quotesCreatesPaysAndRefundsOrdersWithImmutableSnapshotsAndIdempotency() throws Exception {
        String token = login();
        String dependencyCode = uniqueModuleCode("base");
        String mainCode = uniqueModuleCode("institution");
        createOnSaleModule(token, dependencyCode, "基础能力", "200.00", 20);
        createOnSaleModule(token, mainCode, "机构康养", "500.00", 20);
        createVersion(token, dependencyCode, "1.0.0");
        createVersion(token, mainCode, "1.0.0");

        mvc.perform(put("/api/platform/v1/modules/{code}/dependencies", mainCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dependencies":[
                                  {"moduleCode":"%s","type":"REQUIRED","minimumVersion":"1.0.0"}
                                ]}
                                """.formatted(dependencyCode)))
                .andExpect(status().isOk());

        String enterpriseId = createEnterprise(token);
        String planCode = uniquePlanCode("suite");
        createPlan(token, planCode, dependencyCode, mainCode);

        mvc.perform(get("/api/platform/v1/plans")
                        .header("Authorization", bearer(token))
                        .queryParam("enterpriseId", enterpriseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].planCode").value(planCode))
                .andExpect(jsonPath("$.data[0].amount").value("650.00"));

        String planQuoteBody = """
                {"enterpriseId":"%s","planCode":"%s","items":[]}
                """.formatted(enterpriseId, planCode);
        mvc.perform(post("/api/platform/v1/orders/quote")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planQuoteBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalAmount").value("650.00"))
                .andExpect(jsonPath("$.data.payableAmount").value("650.00"))
                .andExpect(jsonPath("$.data.items.length()").value(2));

        String missingDependencyBody = directBody(
                enterpriseId,
                mainCode,
                20
        );
        mvc.perform(post("/api/platform/v1/orders/quote")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingDependencyBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_MISSING"));

        String failedKey = "idem_failed_" + UUID.randomUUID();
        mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", failedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingDependencyBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_MISSING"));
        assertThat(count("SELECT COUNT(*) FROM pf_subscription_order WHERE enterprise_id = (SELECT id FROM pf_enterprise WHERE public_id = :id)", enterpriseId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM pf_idempotency_record WHERE idempotency_key = :id", failedKey)).isZero();

        String orderBody = combinedBody(enterpriseId, dependencyCode, mainCode);
        String key = "idem_order_" + UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.originalAmount").value("700.00"))
                .andExpect(jsonPath("$.data.payableAmount").value("700.00"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].moduleCode").value(dependencyCode))
                .andExpect(jsonPath("$.data.items[0].moduleName").value("基础能力"))
                .andExpect(jsonPath("$.data.items[0].standardAmount").value("200.00"))
                .andExpect(jsonPath("$.data.items[1].standardAmount").value("500.00"))
                .andReturn();
        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("publicId").asText();
        assertThat(orderId).startsWith("order_");

        String requestHash = jdbcClient.sql("""
                        SELECT request_hash
                        FROM pf_subscription_order
                        WHERE public_id = :publicId
                        """)
                .param("publicId", orderId)
                .query(String.class)
                .single();
        String orderStatus = jdbcClient.sql("""
                        SELECT status
                        FROM pf_subscription_order
                        WHERE public_id = :publicId
                        """)
                .param("publicId", orderId)
                .query(String.class)
                .single();
        assertThat(requestHash).hasSize(64);
        assertThat(orderStatus).isEqualTo("PENDING_PAYMENT");
        assertThat(count("SELECT COUNT(*) FROM pf_subscription_order_item WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)", orderId)).isEqualTo(2);

        mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.publicId").value(orderId));

        mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planQuoteBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        long instanceId = createDeploymentInstance(enterpriseId);

        String transactionNo = "mock_" + UUID.randomUUID();
        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"MOCK","channelTransactionNo":"%s"}
                                """.formatted(transactionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FULFILLED"))
                .andExpect(jsonPath("$.data.payments.length()").value(1))
                .andExpect(jsonPath("$.data.payments[0].channelTransactionNo").value(transactionNo));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"MOCK","channelTransactionNo":"%s"}
                                """.formatted(transactionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FULFILLED"));
        assertThat(count("SELECT COUNT(*) FROM pf_payment_transaction WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)", orderId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pf_entitlement WHERE enterprise_id = (SELECT id FROM pf_enterprise WHERE public_id = :id) AND license_type = 'FORMAL' AND status = 'ACTIVE'", enterpriseId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM pf_subscription_order_item WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id) AND entitlement_result = 'APPLIED'", orderId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", instanceId)).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT license_version FROM pf_deployment_instance WHERE id = :id")
                .param("id", instanceId)
                .query(Long.class)
                .single()).isEqualTo(1L);

        String licensePayload = jdbcClient.sql("SELECT payload_json FROM pf_license WHERE instance_id = :id")
                .param("id", instanceId)
                .query(String.class)
                .single();
        JsonNode licensedModules = objectMapper.readTree(licensePayload).path("modules");
        assertThat(licensedModules).hasSize(2);
        List<String> licensedModuleCodes = new ArrayList<>();
        licensedModules.forEach(module -> {
            licensedModuleCodes.add(module.path("code").asText());
            assertThat(module.path("status").asText()).isEqualTo("ACTIVE");
        });
        List<String> expectedModuleOrder = jdbcClient.sql("""
                        SELECT m.code
                        FROM pf_entitlement e
                        JOIN pf_module_definition m ON m.id = e.module_id
                        WHERE e.enterprise_id = (SELECT id FROM pf_enterprise WHERE public_id = :enterpriseId)
                        ORDER BY m.menu_order, m.id
                        """)
                .param("enterpriseId", enterpriseId)
                .query(String.class)
                .list();
        assertThat(licensedModuleCodes).containsExactlyElementsOf(expectedModuleOrder);

        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"MOCK","channelTransactionNo":"mock_other_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM pf_payment_transaction WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)", orderId)).isEqualTo(1);

        mvc.perform(get("/api/platform/v1/orders/{publicId}", orderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FULFILLED"))
                .andExpect(jsonPath("$.data.items[1].moduleName").value("机构康养"));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/refund", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"客户申请退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.refunds.length()").value(1))
                .andExpect(jsonPath("$.data.refunds[0].reason").value("客户申请退款"));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/refund", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"重复退款"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM pf_refund WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)", orderId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pf_audit_log WHERE target_public_id = :id AND action IN ('ORDER_CREATED','ORDER_PAID_SIMULATED','ORDER_REFUNDED')", orderId)).isEqualTo(3);
    }

    @Test
    void rejectsRefundBeforePaymentWithoutPartialWrites() throws Exception {
        String token = login();
        String moduleCode = uniqueModuleCode("membership");
        createOnSaleModule(token, moduleCode, "会员运营", "300.00", 10);
        createVersion(token, moduleCode, "1.0.0");
        String enterpriseId = createEnterprise(token);
        String body = directBody(enterpriseId, moduleCode, 10);

        MvcResult created = mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "idem_pending_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("publicId").asText();

        mvc.perform(post("/api/platform/v1/orders/{publicId}/refund", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"未支付不应退款"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));

        assertThat(count("SELECT COUNT(*) FROM pf_refund WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)", orderId)).isZero();
        assertThat(jdbcClient.sql("SELECT status FROM pf_subscription_order WHERE public_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void platformCanRevokeAndRestoreACompletedOrdersModuleWithoutChangingPaymentHistory() throws Exception {
        String token=login();String moduleCode=uniqueModuleCode("revoke");createOnSaleModule(token,moduleCode,"会员模块","300.00",10);
        String enterpriseId=createEnterprise(token);long instanceId=createDeploymentInstance(enterpriseId);
        MvcResult created=mvc.perform(post("/api/platform/v1/orders").header("Authorization",bearer(token)).header("Idempotency-Key","idem_revoke_"+UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(directBody(enterpriseId,moduleCode,10)))
                .andExpect(status().isCreated()).andReturn();
        String orderId=objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("publicId").asText();
        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated",orderId).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"MOCK","channelTransactionNo":"revoke_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FULFILLED"));
        mvc.perform(post("/api/platform/v1/orders/{publicId}/items/{moduleCode}/revoke",orderId,moduleCode).header("Authorization",bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason":"合同违约，暂停服务"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FULFILLED"))
                .andExpect(jsonPath("$.data.items[0].entitlementResult").value("REVOKED"));
        assertThat(jdbcClient.sql("SELECT status FROM pf_entitlement WHERE enterprise_id=(SELECT id FROM pf_enterprise WHERE public_id=:enterprise) AND module_id=(SELECT id FROM pf_module_definition WHERE code=:code)")
                .param("enterprise",enterpriseId).param("code",moduleCode).query(String.class).single()).isEqualTo("SUSPENDED");
        mvc.perform(post("/api/platform/v1/orders/{publicId}/items/{moduleCode}/restore",orderId,moduleCode).header("Authorization",bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason":"违约问题已解决"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].entitlementResult").value("APPLIED"));
        assertThat(jdbcClient.sql("SELECT status FROM pf_entitlement WHERE enterprise_id=(SELECT id FROM pf_enterprise WHERE public_id=:enterprise) AND module_id=(SELECT id FROM pf_module_definition WHERE code=:code)")
                .param("enterprise",enterpriseId).param("code",moduleCode).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM pf_payment_transaction WHERE order_id=(SELECT id FROM pf_subscription_order WHERE public_id=:id)",orderId)).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT license_version FROM pf_deployment_instance WHERE id=:id").param("id",instanceId).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void canRevokeAndRestoreTheCurrentRenewalOrderForAModule() throws Exception {
        String token = login();
        String moduleCode = uniqueModuleCode("renew-revoke");
        createOnSaleModule(token, moduleCode, "续期撤销模块", "300.00", 10);
        String enterpriseId = createEnterprise(token);
        String firstOrderId = createAndFulfillOrder(token, enterpriseId, moduleCode, "first-renew-revoke");
        String renewalOrderId = createAndFulfillOrder(token, enterpriseId, moduleCode, "second-renew-revoke");

        mvc.perform(get("/api/platform/v1/orders/{publicId}", renewalOrderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].entitlementResult").value("RENEWED"));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/items/{moduleCode}/revoke", renewalOrderId, moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"续费合同违约\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].entitlementResult").value("REVOKED"));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/items/{moduleCode}/restore", renewalOrderId, moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"合同违约已解决\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].entitlementResult").value("RENEWED"));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/items/{moduleCode}/revoke", firstOrderId, moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不能撤销已被续期覆盖的旧授权来源\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void repurchaseAfterRevocationDoesNotCarryRevokedTime() throws Exception {
        String token = login();
        String code = uniqueModuleCode("repurchase");
        createOnSaleModule(token, code, "重新购买模块", "300.00", 10);
        String enterprise = createEnterprise(token);
        String oldOrder = createAndFulfillOrder(token, enterprise, code, "revoked-original");
        mvc.perform(post("/api/platform/v1/orders/{id}/items/{code}/revoke", oldOrder, code)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"违约撤销后重新购买\"}"))
                .andExpect(status().isOk());
        String newOrder = createAndFulfillOrder(token, enterprise, code, "repurchased");
        mvc.perform(get("/api/platform/v1/orders/{id}", newOrder).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].entitlementResult").value("APPLIED"));
        var days = jdbcClient.sql("""
                SELECT starts_at, expires_at FROM pf_entitlement
                WHERE source_order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)
                """).param("id", newOrder).query((rs, row) ->
                java.time.Duration.between(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()).toDays()).single();
        assertThat(days).isEqualTo(365);
    }

    private String createAndFulfillOrder(String token, String enterpriseId, String moduleCode, String keyPrefix) throws Exception {
        MvcResult created = mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", keyPrefix + "_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(enterpriseId, moduleCode, 10)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("publicId").asText();
        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"MOCK\",\"channelTransactionNo\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
        return orderId;
    }

    @Test
    void listsOrdersAndEditsOnlyPendingOrderContents() throws Exception {
        String token = login();
        String moduleCode = uniqueModuleCode("editable");
        createOnSaleModule(token, moduleCode, "可编辑会员模块", "300.00", 10);
        mvc.perform(put("/api/platform/v1/modules/{code}/prices", moduleCode)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prices":[
                                  {"billingMode":"PERIOD","durationDays":365,"amount":"300.00","currency":"CNY","storeQuota":10},
                                  {"billingMode":"PERIOD","durationDays":180,"amount":"300.00","currency":"CNY","storeQuota":5}
                                ]}
                                """))
                .andExpect(status().isOk());
        String enterpriseId = createEnterprise(token);
        MvcResult created = mvc.perform(post("/api/platform/v1/orders")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "idem_edit_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(enterpriseId, moduleCode, 10)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("publicId").asText();

        mvc.perform(get("/api/platform/v1/orders").header("Authorization", bearer(token))
                        .queryParam("enterpriseId", enterpriseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].publicId").value(orderId));

        mvc.perform(put("/api/platform/v1/orders/{publicId}", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enterpriseId":"%s","items":[{"moduleCode":"%s","billingMode":"PERIOD","durationDays":180,"storeQuota":5}],"period":{"billingMode":"PERIOD","durationDays":180,"storeQuota":5}}
                                """.formatted(enterpriseId, moduleCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.payableAmount").value(300.00))
                .andExpect(jsonPath("$.data.items[0].storeQuota").value(5));

        assertThat(jdbcClient.sql("SELECT store_quota FROM pf_subscription_order_item WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)")
                .param("id", orderId).query(Integer.class).single()).isEqualTo(5);
        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated",orderId).header("Authorization",bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"channel":"MOCK","channelTransactionNo":"edit_lock_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/platform/v1/orders/{publicId}",orderId).header("Authorization",bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(directBody(enterpriseId,moduleCode,10)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
    }

    private void createPlan(String token, String planCode, String dependencyCode, String mainCode) throws Exception {
        mvc.perform(post("/api/platform/v1/plans")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"%s",
                                  "name":"区域养老基础套餐",
                                  "description":"基础能力与机构康养组合",
                                  "amount":"650.00",
                                  "minimumAmount":"0.00",
                                  "items":[
                                    {"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":20},
                                    {"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":20}
                                  ]
                                }
                                """.formatted(planCode, dependencyCode, mainCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planCode").value(planCode))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    private void createOnSaleModule(
            String token,
            String moduleCode,
            String name,
            String amount,
            int storeQuota
    ) throws Exception {
        mvc.perform(post("/api/platform/v1/modules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"%s",
                                  "name":"%s",
                                  "description":"订单集成测试模块",
                                  "menuOrder":%d,
                                  "trialEnabled":false,
                                  "dependencies":[],
                                  "prices":[
                                    {"billingMode":"PERIOD","durationDays":365,"amount":"%s",
                                     "currency":"CNY","storeQuota":%d}
                                  ]
                                }
                                """.formatted(
                                        moduleCode,
                                        name,
                                        ThreadLocalRandom.current().nextInt(1000, 1_000_000),
                                        amount,
                                        storeQuota
                                )))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/platform/v1/modules/{code}/status", moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ON_SALE"}
                                """))
                .andExpect(status().isOk());
    }

    private void createVersion(String token, String moduleCode, String version) throws Exception {
        mvc.perform(post("/api/platform/v1/modules/{code}/versions", moduleCode)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"%s","minCoreVersion":"1.0.0"}
                                """.formatted(version)))
                .andExpect(status().isCreated());
    }

    private String createEnterprise(String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/platform/v1/enterprises")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"订单测试企业","contactName":"王凯文","contactPhone":"13800000000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("publicId").asText();
    }

    private long createDeploymentInstance(String enterpriseId) {
        String publicId = "inst_" + UUID.randomUUID().toString().replace("-", "");
        int inserted = jdbcClient.sql("""
                        INSERT INTO pf_deployment_instance(
                            public_id, enterprise_id, installation_id, tenant_database_id,
                            public_key_pem, fingerprint, status, license_version, created_at, updated_at
                        )
                        SELECT :publicId, id, :installationId, :tenantDatabaseId,
                               'test-public-key', :fingerprint, 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        FROM pf_enterprise
                        WHERE public_id = :enterpriseId
                        """)
                .param("publicId", publicId)
                .param("installationId", "install_" + UUID.randomUUID())
                .param("tenantDatabaseId", "tenant_" + UUID.randomUUID())
                .param("fingerprint", "fp_" + UUID.randomUUID())
                .param("enterpriseId", enterpriseId)
                .update();
        assertThat(inserted).isOne();
        return jdbcClient.sql("SELECT id FROM pf_deployment_instance WHERE public_id = :id")
                .param("id", publicId)
                .query(Long.class)
                .single();
    }

    private String directBody(String enterpriseId, String moduleCode, int storeQuota) {
        return """
                {
                  "enterpriseId":"%s",
                  "items":[{"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":%d}],
                  "period":{"billingMode":"PERIOD","durationDays":365,"storeQuota":%d}
                }
                """.formatted(enterpriseId, moduleCode, storeQuota, storeQuota);
    }

    private String combinedBody(
            String enterpriseId,
            String dependencyCode,
            String mainCode
    ) {
        return """
                {
                  "enterpriseId":"%s",
                  "items":[
                    {"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":20},
                    {"moduleCode":"%s","billingMode":"PERIOD","durationDays":365,"storeQuota":20}
                  ],
                  "period":{"billingMode":"PERIOD","durationDays":365,"storeQuota":20}
                }
                """.formatted(enterpriseId, dependencyCode, mainCode);
    }

    private long count(String sql, Object parameter) {
        return jdbcClient.sql(sql)
                .param("id", parameter)
                .query(Long.class)
                .single();
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/platform/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "ChangeMe-Platform-2026"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private static String uniqueModuleCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniquePlanCode(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
