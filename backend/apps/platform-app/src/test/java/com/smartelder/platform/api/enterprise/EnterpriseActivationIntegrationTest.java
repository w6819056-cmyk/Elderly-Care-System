package com.smartelder.platform.api.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class EnterpriseActivationIntegrationTest {

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
    void activatesInstanceIssuesVerifiableLicenseAndRejectsCodeReuse() throws Exception {
        String token = login();
        CreatedEnterprise enterprise = createEnterprise(token);
        String activationCode = createActivationCode(token, enterprise.publicId(), 5);
        String storedCodeHash = jdbcClient.sql("""
                        SELECT code_hash FROM pf_activation_code
                        WHERE enterprise_id = (SELECT id FROM pf_enterprise WHERE public_id = :publicId)
                        """)
                .param("publicId", enterprise.publicId())
                .query(String.class)
                .single();
        assertThat(storedCodeHash).matches("[0-9a-f]{64}").isNotEqualTo(activationCode);

        mvc.perform(get("/api/platform/v1/enterprises")
                        .header("Authorization", bearer(token))
                        .queryParam("status", "ACTIVE")
                        .queryParam("page", "1")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.publicId == '%s')]".formatted(enterprise.publicId()))
                        .exists());

        String publicKeyPem = toPem("PUBLIC KEY", INSTANCE_KEY_PAIR.getPublic().getEncoded());
        Activation activation = activate(activationCode, publicKeyPem, "install-001", "tenant-db-001");
        assertThat(activation.licenseVersion()).isEqualTo(1);
        assertThat(activation.license().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(activation.license().path("signatureAlgorithm").asText()).isEqualTo("Ed25519");
        assertThat(verifyLicenseSignature(activation.license())).isTrue();

        SignedRequest emptyRequest = signedRequest(
                activation.instancePublicId(),
                "GET",
                "/api/platform/v1/instances/" + activation.instancePublicId() + "/license",
                "",
                INSTANCE_KEY_PAIR.getPrivate()
        );
        mvc.perform(get("/api/platform/v1/instances/{publicId}/license", activation.instancePublicId())
                        .headers(emptyRequest.headers()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.payloadJson").isString())
                .andExpect(jsonPath("$.data.signature").isString());

        String heartbeatBody = objectMapper.writeValueAsString(Map.of(
                "licenseVersion", 1,
                "clientTime", Instant.now().toString()
        ));
        SignedRequest heartbeat = signedRequest(
                activation.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/heartbeat",
                heartbeatBody,
                INSTANCE_KEY_PAIR.getPrivate()
        );
        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(heartbeat.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instancePublicId").value(activation.instancePublicId()))
                .andExpect(jsonPath("$.data.licenseVersion").value(1));

        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(heartbeat.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSTANCE_SIGNATURE_INVALID"));

        Integer heartbeatCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM pf_heartbeat WHERE instance_id = (
                            SELECT id FROM pf_deployment_instance WHERE public_id = :publicId
                        )
                        """)
                .param("publicId", activation.instancePublicId())
                .query(Integer.class)
                .single();
        assertThat(heartbeatCount).isEqualTo(1);

        mvc.perform(post("/api/platform/v1/instances/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activationCode", activationCode,
                                "installationId", "install-002",
                                "tenantDatabaseId", "tenant-db-002",
                                "publicKeyPem", publicKeyPem,
                                "fingerprint", "fingerprint-002"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVATION_CODE_USED"));
    }

    @Test
    void rejectsHeartbeatWhenSignedBodyIsTampered() throws Exception {
        String token = login();
        CreatedEnterprise enterprise = createEnterprise(token);
        String activationCode = createActivationCode(token, enterprise.publicId(), 5);
        String publicKeyPem = toPem("PUBLIC KEY", INSTANCE_KEY_PAIR.getPublic().getEncoded());
        Activation activation = activate(
                activationCode,
                publicKeyPem,
                "install-tamper-" + UUID.randomUUID(),
                "tenant-db-tamper-" + UUID.randomUUID()
        );

        String originalBody = objectMapper.writeValueAsString(Map.of(
                "licenseVersion", 1,
                "clientTime", Instant.now().toString()
        ));
        String tamperedBody = objectMapper.writeValueAsString(Map.of(
                "licenseVersion", 999,
                "clientTime", Instant.now().toString()
        ));
        SignedRequest signed = signedRequest(
                activation.instancePublicId(),
                "POST",
                "/api/platform/v1/instances/heartbeat",
                originalBody,
                INSTANCE_KEY_PAIR.getPrivate()
        );

        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .headers(signed.headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSTANCE_SIGNATURE_INVALID"));
    }

    @Test
    void rejectsRequestsOutsideFiveMinuteClockWindow() throws Exception {
        String token = login();
        CreatedEnterprise enterprise = createEnterprise(token);
        String activationCode = createActivationCode(token, enterprise.publicId(), 5);
        String publicKeyPem = toPem("PUBLIC KEY", INSTANCE_KEY_PAIR.getPublic().getEncoded());
        Activation activation = activate(
                activationCode,
                publicKeyPem,
                "install-skew-" + UUID.randomUUID(),
                "tenant-db-skew-" + UUID.randomUUID()
        );
        String heartbeatBody = objectMapper.writeValueAsString(Map.of(
                "licenseVersion", 1,
                "clientTime", Instant.now().toString()
        ));

        for (Instant timestamp : new Instant[]{
                Instant.now().minusSeconds(301),
                Instant.now().plusSeconds(301)
        }) {
            SignedRequest signed = signedRequestAt(
                    activation.instancePublicId(),
                    "POST",
                    "/api/platform/v1/instances/heartbeat",
                    heartbeatBody,
                    INSTANCE_KEY_PAIR.getPrivate(),
                    timestamp,
                    "nonce-skew-" + UUID.randomUUID()
            );
            mvc.perform(post("/api/platform/v1/instances/heartbeat")
                            .headers(signed.headers())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(heartbeatBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("INSTANCE_SIGNATURE_INVALID"));
        }
    }

    @Test
    void rejectsOversizedInstanceRequestBodies() throws Exception {
        String oversizedBody = "x".repeat(64 * 1024 + 1);
        mvc.perform(post("/api/platform/v1/instances/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
    @Test
    void enterpriseLifecycleRejectsInvalidTransitionAndSuspendedActivation() throws Exception {
        String token = login();
        CreatedEnterprise enterprise = createEnterprise(token);
        String activationCode = createActivationCode(token, enterprise.publicId(), 5);

        mvc.perform(patch("/api/platform/v1/enterprises/{publicId}/status", enterprise.publicId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        mvc.perform(post("/api/platform/v1/instances/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activationCode", activationCode,
                                "installationId", "install-suspended",
                                "tenantDatabaseId", "tenant-db-suspended",
                                "publicKeyPem", toPem(
                                        "PUBLIC KEY",
                                        INSTANCE_KEY_PAIR.getPublic().getEncoded()
                                ),
                                "fingerprint", "fingerprint-suspended"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_SUSPENDED"));

        mvc.perform(patch("/api/platform/v1/enterprises/{publicId}/status", enterprise.publicId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CLOSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        mvc.perform(patch("/api/platform/v1/enterprises/{publicId}/status", enterprise.publicId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_STATE_CONFLICT"));
    }

    @Test
    void activationCodeStopsWorkingAfterConfiguredFailedAttempts() throws Exception {
        String token = login();
        CreatedEnterprise enterprise = createEnterprise(token);
        String activationCode = createActivationCode(token, enterprise.publicId(), 2);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/platform/v1/instances/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "activationCode", activationCode,
                                    "installationId", "wrong-install-" + attempt,
                                    "tenantDatabaseId", "wrong-db-" + attempt,
                                    "publicKeyPem", "not-a-public-key",
                                    "fingerprint", "wrong-fingerprint-" + attempt
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACTIVATION_CODE_INVALID"));
        }

        mvc.perform(post("/api/platform/v1/instances/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activationCode", activationCode,
                                "installationId", "too-late-install",
                                "tenantDatabaseId", "too-late-db",
                                "publicKeyPem", toPem(
                                        "PUBLIC KEY",
                                        INSTANCE_KEY_PAIR.getPublic().getEncoded()
                                ),
                                "fingerprint", "too-late-fingerprint"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTIVATION_CODE_INVALID"));
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/platform/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"ChangeMe-Platform-2026"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private CreatedEnterprise createEnterprise(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String response = mvc.perform(post("/api/platform/v1/enterprises")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "康颐测试集团-" + suffix,
                                "contactName", "王凯文",
                                "contactPhone", "13800000000"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.publicId").isString())
                .andExpect(jsonPath("$.data.orgNo").isString())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new CreatedEnterprise(data.path("publicId").asText(), data.path("orgNo").asText());
    }

    private String createActivationCode(String token, String enterprisePublicId, int maxAttempts)
            throws Exception {
        String response = mvc.perform(post(
                                "/api/platform/v1/enterprises/{publicId}/activation-codes",
                                enterprisePublicId
                        )
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ttlHours", 24,
                                "maxAttempts", maxAttempts
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activationCode").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("activationCode").asText();
    }

    private Activation activate(
            String activationCode,
            String publicKeyPem,
            String installationId,
            String tenantDatabaseId
    ) throws Exception {
        String fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        INSTANCE_KEY_PAIR.getPublic().getEncoded()
                )
        );
        String response = mvc.perform(post("/api/platform/v1/instances/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activationCode", activationCode,
                                "installationId", installationId,
                                "tenantDatabaseId", tenantDatabaseId,
                                "publicKeyPem", publicKeyPem,
                                "fingerprint", fingerprint
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instancePublicId").isString())
                .andExpect(jsonPath("$.data.licenseVersion").value(1))
                .andExpect(jsonPath("$.data.license").isMap())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new Activation(
                data.path("instancePublicId").asText(),
                data.path("licenseVersion").asLong(),
                data.path("license")
        );
    }

    private boolean verifyLicenseSignature(JsonNode license) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(LICENSE_KEY_PAIR.getPublic());
        verifier.update(license.path("payloadJson").asText().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(license.path("signature").asText()));
    }

    private SignedRequest signedRequest(
            String instanceId,
            String method,
            String path,
            String body,
            PrivateKey privateKey
    ) throws Exception {
        return signedRequestAt(
                instanceId,
                method,
                path,
                body,
                privateKey,
                Instant.now(),
                "nonce-" + UUID.randomUUID()
        );
    }

    private SignedRequest signedRequestAt(
            String instanceId,
            String method,
            String path,
            String body,
            PrivateKey privateKey,
            Instant timestampValue,
            String nonce
    ) throws Exception {
        String timestamp = timestampValue.toString();
        String canonical = method + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + sha256Hex(body);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Instance-Id", instanceId);
        headers.set("X-Instance-Timestamp", timestamp);
        headers.set("X-Instance-Nonce", nonce);
        headers.set("X-Instance-Signature", signature);
        return new SignedRequest(headers);
    }

    private record SignedRequest(org.springframework.http.HttpHeaders headers) {
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

    private record CreatedEnterprise(String publicId, String orgNo) {
    }

    private record Activation(String instancePublicId, long licenseVersion, JsonNode license) {
    }
}



