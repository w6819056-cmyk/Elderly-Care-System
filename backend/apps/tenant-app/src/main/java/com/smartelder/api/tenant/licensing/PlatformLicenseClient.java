package com.smartelder.api.tenant.licensing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.platform.licensing.LicenseKeyPair;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class PlatformLicenseClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final TenantLicenseProperties properties;
    private final InstanceRequestSigner requestSigner;
    private final LicenseKeyPair instanceKeyPair;

    public PlatformLicenseClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            TenantLicenseProperties properties,
            InstanceRequestSigner requestSigner,
            LicenseKeyPair instanceKeyPair) {
        this.client = builder.baseUrl(properties.getPlatformBaseUrl()).build();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = properties;
        this.requestSigner = requestSigner;
        this.instanceKeyPair = instanceKeyPair;
    }

    public JsonNode heartbeat(long licenseVersion) {
        String body = write(Map.of("licenseVersion", licenseVersion, "clientTime", Instant.now().toString()));
        return signed("POST", "/api/platform/v1/instances/heartbeat", body);
    }

    public JsonNode currentLicense() {
        String path = "/api/platform/v1/instances/" + properties.getInstancePublicId() + "/license";
        return signed("GET", path, "");
    }

    public JsonNode acknowledge(long version, String sha256) {
        String path = "/api/platform/v1/instances/" + properties.getInstancePublicId() + "/ack-license";
        return signed("POST", path, write(Map.of("version", version, "sha256", sha256)));
    }

    public JsonNode activate() {
        String publicKeyPem = instanceKeyPair.publicKeyPem();
        String body = write(Map.of(
                "activationCode", properties.getActivationCode(),
                "installationId", properties.getInstallationId(),
                "tenantDatabaseId", properties.getTenantDatabaseId(),
                "publicKeyPem", publicKeyPem,
                "fingerprint", fingerprint(instanceKeyPair)));
        return client.post().uri("/api/platform/v1/instances/activate")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }

    private JsonNode signed(String method, String path, String body) {
        InstanceRequestSigner.SignedHeaders signed = requestSigner.sign(method, path, body);
        RestClient.RequestBodySpec request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .headers(headers -> instanceHeaders(headers, signed));
        if (body == null || body.isEmpty()) {
            return request.retrieve().body(JsonNode.class);
        }
        return request.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }

    private void instanceHeaders(HttpHeaders headers, InstanceRequestSigner.SignedHeaders signed) {
        headers.set("X-Instance-Id", properties.getInstancePublicId());
        headers.set("X-Instance-Timestamp", signed.timestamp());
        headers.set("X-Instance-Nonce", signed.nonce());
        headers.set("X-Instance-Signature", signed.signature());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize platform request", exception);
        }
    }

    private static String fingerprint(LicenseKeyPair pair) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(pair.publicKey().getEncoded()));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
