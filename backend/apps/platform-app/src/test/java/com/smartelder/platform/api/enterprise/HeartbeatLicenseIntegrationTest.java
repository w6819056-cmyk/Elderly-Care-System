package com.smartelder.platform.api.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.PlatformApplication;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class HeartbeatLicenseIntegrationTest {

    private static final KeyPair LICENSE_KEY_PAIR = generateEd25519KeyPair();
    private static final KeyPair INSTANCE_KEY_PAIR = generateEd25519KeyPair();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void licenseSigningKey(DynamicPropertyRegistry registry) {
        registry.add("platform.license.signing-private-key", () -> toPem(
                "PRIVATE KEY",
                LICENSE_KEY_PAIR.getPrivate().getEncoded()
        ));
    }

    @Test
    void heartbeatReturnsLicenseOnlyWhenClientVersionIsBehindWithoutIssuingDuplicates() throws Exception {
        ActivatedInstance activated = activateInstance();

        String behindBody = heartbeatBody(0);
        SignedRequest behindRequest = signedRequest(
                activated.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/heartbeat",
                behindBody
        );
        MvcResult first = mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(behindRequest.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(behindBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformTime").isString())
                .andExpect(jsonPath("$.data.instanceStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.licenseVersion").value(1))
                .andExpect(jsonPath("$.data.licensePayload.version").value(1))
                .andExpect(jsonPath("$.data.licensePayload.payloadJson").isString())
                .andExpect(jsonPath("$.data.licensePayload.signature").isString())
                .andExpect(jsonPath("$.data.nextHeartbeatAt").isString())
                .andReturn();
        JsonNode firstData = objectMapper.readTree(first.getResponse().getContentAsString()).path("data");
        assertThat(Instant.parse(firstData.path("nextHeartbeatAt").asText()))
                .isAfter(Instant.parse(firstData.path("platformTime").asText()));

        String currentBody = heartbeatBody(1);
        SignedRequest currentRequest = signedRequest(
                activated.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/heartbeat",
                currentBody
        );
        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(currentRequest.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(currentBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.licenseVersion").value(1))
                .andExpect(jsonPath("$.data.licensePayload").value(nullValue()));

        assertThat(count("""
                SELECT COUNT(*) FROM pf_heartbeat
                WHERE instance_id = (SELECT id FROM pf_deployment_instance WHERE public_id = :id)
                """, activated.instancePublicId())).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM pf_license
                WHERE instance_id = (SELECT id FROM pf_deployment_instance WHERE public_id = :id)
                """, activated.instancePublicId())).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM pf_audit_log
                WHERE action = 'LICENSE_ISSUED' AND target_public_id = :id
                """, activated.instancePublicId())).isEqualTo(1);
    }

    @Test
    void acknowledgesLatestLicenseIdempotently() throws Exception {
        ActivatedInstance activated = activateInstance();
        String body = objectMapper.writeValueAsString(Map.of("version", 1));

        SignedRequest firstRequest = signedRequest(
                activated.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/" + activated.instancePublicId() + "/ack-license",
                body
        );
        MvcResult first = mvc.perform(post(
                                "/api/platform/v1/instances/{publicId}/ack-license",
                                activated.instancePublicId()
                        )
                        .headers(firstRequest.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.acknowledgedAt").isString())
                .andReturn();
        String firstAcknowledgedAt = objectMapper.readTree(
                        first.getResponse().getContentAsString())
                .path("data")
                .path("acknowledgedAt")
                .asText();

        SignedRequest secondRequest = signedRequest(
                activated.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/" + activated.instancePublicId() + "/ack-license",
                body
        );
        mvc.perform(post("/api/platform/v1/instances/{publicId}/ack-license", activated.instancePublicId())
                        .headers(secondRequest.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.acknowledgedAt").value(firstAcknowledgedAt));

        Timestamp acknowledgedAt = jdbcClient.sql("""
                        SELECT acknowledged_at FROM pf_license
                        WHERE instance_id = (SELECT id FROM pf_deployment_instance WHERE public_id = :id)
                          AND version = 1
                        """)
                .param("id", activated.instancePublicId())
                .query(Timestamp.class)
                .single();
        assertThat(acknowledgedAt).isNotNull();
    }

    @Test
    void rejectsHeartbeatDuringEnterpriseSuspensionAndResumesAfterReactivation() throws Exception {
        ActivatedInstance activated = activateInstance();

        mvc.perform(patch("/api/platform/v1/enterprises/{publicId}/status", activated.enterprisePublicId())
                        .header("Authorization", bearer(activated.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        String suspendedBody = heartbeatBody(1);
        SignedRequest suspendedRequest = signedRequest(
                activated.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/heartbeat",
                suspendedBody
        );
        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(suspendedRequest.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendedBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_SUSPENDED"));

        mvc.perform(patch("/api/platform/v1/enterprises/{publicId}/status", activated.enterprisePublicId())
                        .header("Authorization", bearer(activated.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        String resumedBody = heartbeatBody(1);
        SignedRequest resumedRequest = signedRequest(
                activated.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/heartbeat",
                resumedBody
        );
        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(resumedRequest.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceStatus").value("ONLINE"));
    }

    @Test
    void retriesLegacyFulfillingOrderAndPublishesLicenseRefreshEventIdempotently() throws Exception {
        ActivatedInstance activated = activateInstance();
        String moduleCode = "module-" + UUID.randomUUID().toString().substring(0, 8);
        SeededOrder order = seedFulfillingOrder(
                activated.enterprisePublicId(),
                moduleCode,
                false,
                1
        );

        mvc.perform(post("/api/platform/v1/orders/{publicId}/retry-fulfillment", order.publicId())
                        .header("Authorization", bearer(activated.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FULFILLED"));

        mvc.perform(post("/api/platform/v1/orders/{publicId}/retry-fulfillment", order.publicId())
                        .header("Authorization", bearer(activated.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FULFILLED"));

        assertThat(countForEnterpriseModule(
                "SELECT COUNT(*) FROM pf_entitlement WHERE enterprise_id = :enterpriseId AND module_id = :moduleId",
                activated.enterprisePublicId(),
                order.moduleId()
        )).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM pf_license
                WHERE instance_id = (SELECT id FROM pf_deployment_instance WHERE public_id = :id)
                """, activated.instancePublicId())).isEqualTo(1);
        assertThat(countForInstanceEvent("""
                SELECT COUNT(*) FROM pf_outbox_event
                WHERE aggregate_type = 'DEPLOYMENT_INSTANCE'
                  AND aggregate_id = :instanceId
                  AND event_type = 'license.refresh.requested'
                """, activated.instancePublicId())).isEqualTo(1);
    }

    @Test
    void movesOrderToManualReviewAfterFiveFailedFulfillmentAttempts() throws Exception {
        ActivatedInstance activated = activateInstance();
        String moduleCode = "broken-" + UUID.randomUUID().toString().substring(0, 8);
        SeededOrder order = seedFulfillingOrder(
                activated.enterprisePublicId(),
                moduleCode,
                true,
                0
        );

        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post("/api/platform/v1/orders/{publicId}/retry-fulfillment", order.publicId())
                            .header("Authorization", bearer(activated.token())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_FULFILLMENT_FAILED"));
        }

        Map<String, Object> row = jdbcClient.sql("""
                        SELECT status, fulfillment_attempts, last_fulfillment_error
                        FROM pf_subscription_order
                        WHERE public_id = :publicId
                        """)
                .param("publicId", order.publicId())
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "attempts", rs.getInt("fulfillment_attempts"),
                        "error", rs.getString("last_fulfillment_error")
                ))
                .single();
        assertThat(row.get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat(row.get("attempts")).isEqualTo(5);
        assertThat(row.get("error")).asString().contains("缺少正式授权的依赖模块");
    }

    private ActivatedInstance activateInstance() throws Exception {
        String token = login();
        CreatedEnterprise enterprise = createEnterprise(token);
        String activationCode = createActivationCode(token, enterprise.publicId());
        String publicKeyPem = toPem("PUBLIC KEY", INSTANCE_KEY_PAIR.getPublic().getEncoded());
        String fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        INSTANCE_KEY_PAIR.getPublic().getEncoded()
                )
        );
        MvcResult result = mvc.perform(post("/api/platform/v1/instances/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activationCode", activationCode,
                                "installationId", "install-" + UUID.randomUUID(),
                                "tenantDatabaseId", "tenant-db-" + UUID.randomUUID(),
                                "publicKeyPem", publicKeyPem,
                                "fingerprint", fingerprint
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new ActivatedInstance(
                token,
                enterprise.publicId(),
                data.path("instancePublicId").asText()
        );
    }

    private CreatedEnterprise createEnterprise(String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/platform/v1/enterprises")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "心跳测试企业-" + UUID.randomUUID(),
                                "contactName", "王凯文",
                                "contactPhone", "13800000000"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String publicId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("publicId")
                .asText();
        return new CreatedEnterprise(publicId);
    }

    private String createActivationCode(String token, String enterprisePublicId) throws Exception {
        MvcResult result = mvc.perform(post("/api/platform/v1/enterprises/{publicId}/activation-codes", enterprisePublicId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ttlHours":1,"maxAttempts":5}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("activationCode")
                .asText();
    }

    private SeededOrder seedFulfillingOrder(
            String enterprisePublicId,
            String moduleCode,
            boolean missingDependency,
            int attempts
    ) throws Exception {
        Instant now = Instant.now();
        Timestamp nowTimestamp = Timestamp.from(now);
        long enterpriseId = jdbcClient.sql("SELECT id FROM pf_enterprise WHERE public_id = :id")
                .param("id", enterprisePublicId)
                .query(Long.class)
                .single();
        long adminId = jdbcClient.sql("SELECT id FROM pf_admin_user WHERE username = 'admin'")
                .query(Long.class)
                .single();
        int menuOrder = jdbcClient.sql("SELECT COALESCE(MAX(menu_order), 0) + 1 FROM pf_module_definition")
                .query(Integer.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO pf_module_definition(
                            code, name, description, menu_order, sale_status,
                            trial_enabled, created_at, updated_at, version
                        ) VALUES (
                            :code, :name, '任务八测试模块', :menuOrder, 'ON_SALE',
                            FALSE, :now, :now, 0
                        )
                        """)
                .param("code", moduleCode)
                .param("name", moduleCode)
                .param("menuOrder", menuOrder)
                .param("now", nowTimestamp)
                .update();
        long moduleId = jdbcClient.sql("SELECT id FROM pf_module_definition WHERE code = :code")
                .param("code", moduleCode)
                .query(Long.class)
                .single();

        if (missingDependency) {
            String dependencyCode = "dep-" + UUID.randomUUID().toString().substring(0, 8);
            jdbcClient.sql("""
                            INSERT INTO pf_module_definition(
                                code, name, description, menu_order, sale_status,
                                trial_enabled, created_at, updated_at, version
                            ) VALUES (
                                :code, :name, '任务八缺失依赖', :menuOrder, 'ON_SALE',
                                FALSE, :now, :now, 0
                            )
                            """)
                    .param("code", dependencyCode)
                    .param("name", dependencyCode)
                    .param("menuOrder", menuOrder + 1)
                    .param("now", nowTimestamp)
                    .update();
            long dependencyId = jdbcClient.sql(
                            "SELECT id FROM pf_module_definition WHERE code = :code")
                    .param("code", dependencyCode)
                    .query(Long.class)
                    .single();
            jdbcClient.sql("""
                            INSERT INTO pf_module_dependency(
                                module_id, dependency_module_id, dependency_type, minimum_version
                            ) VALUES (:moduleId, :dependencyId, 'REQUIRED', '1.0.0')
                            """)
                    .param("moduleId", moduleId)
                    .param("dependencyId", dependencyId)
                    .update();
        }

        String orderPublicId = "order_" + UUID.randomUUID().toString().replace("-", "");
        String orderNo = "PO-" + orderPublicId.substring(6, 26).toUpperCase();
        jdbcClient.sql("""
                        INSERT INTO pf_subscription_order(
                            public_id, order_no, enterprise_id, plan_id, status, currency,
                            original_amount, deduction_amount, payable_amount, request_hash,
                            created_by, paid_at, fulfilled_at, fulfillment_attempts,
                            last_fulfillment_error, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :orderNo, :enterpriseId, NULL, 'FULFILLING', 'CNY',
                            100.00, 0.00, 100.00, :requestHash,
                            :createdBy, :paidAt, NULL, :attempts,
                            NULL, :now, :now, 0
                        )
                        """)
                .param("publicId", orderPublicId)
                .param("orderNo", orderNo)
                .param("enterpriseId", enterpriseId)
                .param("requestHash", sha256Hex(orderPublicId))
                .param("createdBy", adminId)
                .param("paidAt", nowTimestamp)
                .param("attempts", attempts)
                .param("now", nowTimestamp)
                .update();
        long orderId = jdbcClient.sql(
                        "SELECT id FROM pf_subscription_order WHERE public_id = :id")
                .param("id", orderPublicId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO pf_subscription_order_item(
                            order_id, module_id, module_code, module_name, billing_mode,
                            duration_days, store_quota, standard_amount, deduction_amount,
                            actual_amount, entitlement_result
                        ) VALUES (
                            :orderId, :moduleId, :moduleCode, :moduleCode, 'PERIOD',
                            365, 10, 100.00, 0.00, 100.00, 'PENDING'
                        )
                        """)
                .param("orderId", orderId)
                .param("moduleId", moduleId)
                .param("moduleCode", moduleCode)
                .update();
        return new SeededOrder(orderPublicId, moduleId);
    }

    private String heartbeatBody(long licenseVersion) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "licenseVersion", licenseVersion,
                "clientTime", Instant.now().toString()
        ));
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/platform/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"ChangeMe-Platform-2026"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private long count(String sql, String id) {
        return jdbcClient.sql(sql)
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long countForEnterpriseModule(String sql, String enterprisePublicId, long moduleId) {
        long enterpriseId = jdbcClient.sql("SELECT id FROM pf_enterprise WHERE public_id = :id")
                .param("id", enterprisePublicId)
                .query(Long.class)
                .single();
        return jdbcClient.sql(sql)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .query(Long.class)
                .single();
    }

    private long countForInstanceEvent(String sql, String instancePublicId) {
        return jdbcClient.sql(sql)
                .param("instanceId", instancePublicId)
                .query(Long.class)
                .single();
    }

    private SignedRequest signedRequest(
            String instanceId,
            String method,
            String path,
            String body
    ) throws Exception {
        String timestamp = Instant.now().toString();
        String nonce = "nonce-" + UUID.randomUUID();
        String canonical = method + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + sha256Hex(body);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(INSTANCE_KEY_PAIR.getPrivate());
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Instance-Id", instanceId);
        headers.set("X-Instance-Timestamp", timestamp);
        headers.set("X-Instance-Nonce", nonce);
        headers.set("X-Instance-Signature", Base64.getEncoder().encodeToString(signer.sign()));
        return new SignedRequest(headers);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static KeyPair generateEd25519KeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String toPem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record CreatedEnterprise(String publicId) {
    }

    private record ActivatedInstance(
            String token,
            String enterprisePublicId,
            String instancePublicId
    ) {
    }

    private record SeededOrder(String publicId, long moduleId) {
    }

    private record SignedRequest(HttpHeaders headers) {
    }
}
