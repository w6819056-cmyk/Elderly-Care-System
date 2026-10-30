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

        String transactionNo = "mock_" + UUID.randomUUID();
        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"MOCK","channelTransactionNo":"%s"}
                                """.formatted(transactionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.payments.length()").value(1))
                .andExpect(jsonPath("$.data.payments[0].channelTransactionNo").value(transactionNo));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/pay-simulated", orderId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"MOCK","channelTransactionNo":"%s"}
                                """.formatted(transactionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        assertThat(count("SELECT COUNT(*) FROM pf_payment_transaction WHERE order_id = (SELECT id FROM pf_subscription_order WHERE public_id = :id)", orderId)).isEqualTo(1);

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
                .andExpect(jsonPath("$.data.status").value("PAID"))
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

    private long count(String sql, String parameter) {
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
