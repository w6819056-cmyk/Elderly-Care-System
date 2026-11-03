package com.smartelder.api.tenant.licensing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.core.licensing.TenantLicenseRepository.LicenseStatus;
import com.smartelder.core.licensing.TenantLicenseRepository.VerifiedLicenseSnapshot;
import com.smartelder.core.licensing.TenantLicenseService;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import com.smartelder.platform.licensing.LicenseDocument;
import com.smartelder.platform.licensing.LicenseSignature;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "app.license", name = "enabled", havingValue = "true")
public class LicenseSyncCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(LicenseSyncCoordinator.class);

    private final PlatformLicenseClient client;
    private final TenantLicenseService tenantLicenseService;
    private final TenantLicenseVerifier verifier;
    private final TenantLicenseProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile int failedAttempts;

    public LicenseSyncCoordinator(
            PlatformLicenseClient client,
            TenantLicenseService tenantLicenseService,
            TenantLicenseVerifier verifier,
            TenantLicenseProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tenantLicenseService = Objects.requireNonNull(tenantLicenseService, "tenantLicenseService must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        attemptSync();
    }

    @Scheduled(fixedDelayString = "${app.license.retry-poll-ms:60000}")
    public void scheduledSync() {
        if (!clock.instant().isBefore(nextAttemptAt)) {
            attemptSync();
        }
    }

    private synchronized void attemptSync() {
        try {
            LicenseStatus current = tenantLicenseService.currentStatus();
            if (current == null && properties.isActivationConfigured()) {
                activateAndAccept();
            } else {
                if (current != null) {
                    properties.setOrgNo(current.orgNo());
                    properties.setInstancePublicId(current.deploymentInstanceId());
                    properties.setInstanceFingerprint(current.instanceFingerprint());
                }
                syncNow();
            }
            failedAttempts = 0;
            nextAttemptAt = clock.instant().plusMillis(properties.getHeartbeatIntervalMs());
        } catch (RuntimeException exception) {
            failedAttempts = Math.min(failedAttempts + 1, 16);
            long backoffSeconds = Math.min(21_600L, 60L << Math.min(failedAttempts - 1, 8));
            long jitterMillis = ThreadLocalRandom.current().nextLong(1_000, 30_001);
            nextAttemptAt = clock.instant().plus(Duration.ofSeconds(backoffSeconds)).plusMillis(jitterMillis);
            LOG.warn("Platform license sync failed; retaining the last verified local snapshot");
        }
    }

    public synchronized void syncNow() {
        LicenseStatus current = tenantLicenseService.currentStatus();
        long version = current == null ? 0 : current.version();
        JsonNode heartbeatData;
        try {
            heartbeatData = client.heartbeat(version).path("data");
        } catch (RestClientResponseException exception) {
            recordExplicitControlStatus(exception);
            throw exception;
        }
        Instant platformTime = parseInstant(heartbeatData.path("platformTime").asText(null));
        if (!"ONLINE".equals(heartbeatData.path("instanceStatus").asText())) {
            throw new LicenseVerificationException("platform reports this instance as inactive");
        }

        JsonNode signedLicense = heartbeatData.path("licensePayload");
        if (signedLicense.isMissingNode() || signedLicense.isNull()) {
            signedLicense = client.currentLicense().path("data");
        }
        acceptAndAcknowledge(signedLicense, platformTime);
    }

    private void activateAndAccept() {
        JsonNode activationData = client.activate().path("data");
        properties.setInstancePublicId(requiredText(activationData, "instancePublicId"));
        JsonNode signedLicense = activationData.path("license");
        LicenseDocument payload = readDocument(requiredText(signedLicense, "payloadJson"));
        properties.setOrgNo(payload.orgNo());
        properties.setInstanceFingerprint(payload.instanceFingerprint());
        acceptAndAcknowledge(signedLicense, parseInstant(signedLicense.path("issuedAt").asText(null)));
        properties.setActivationCode("");
    }

    private void recordExplicitControlStatus(RestClientResponseException exception) {
        try {
            JsonNode error = objectMapper.readTree(exception.getResponseBodyAsString());
            String code = error.path("code").asText();
            if ("ENTERPRISE_SUSPENDED".equals(code)) {
                tenantLicenseService.markPlatformStatus("SUSPENDED");
            } else if ("INSTANCE_REVOKED".equals(code)) {
                tenantLicenseService.markPlatformStatus("REVOKED");
            }
        } catch (JsonProcessingException ignored) {
            // An unrecognized platform error must not be treated as an authorization revocation.
        }
    }

    private void acceptAndAcknowledge(JsonNode transfer, Instant platformTime) {
        String payloadJson = requiredText(transfer, "payloadJson");
        String signatureValue = requiredText(transfer, "signature");
        String algorithm = requiredText(transfer, "signatureAlgorithm");
        String keyId = requiredText(transfer, "keyId");
        LicenseDocument payload = readDocument(payloadJson).withSignature(
                new LicenseSignature(algorithm, keyId, signatureValue));
        String digest = LicenseCanonicalizer.sha256(payload);
        String responseDigest = requiredText(transfer, "sha256");
        if (!digest.equals(responseDigest)) {
            throw new LicenseVerificationException("platform license digest does not match payload");
        }
        String rawJson = writeDocument(payload);
        VerifiedLicenseSnapshot verified = verifier.verify(rawJson,
                new TenantLicenseVerifier.ExpectedInstance(properties.getOrgNo(),
                        properties.getInstancePublicId(), properties.getInstanceFingerprint()),
                platformTime == null ? clock.instant() : platformTime);

        tenantLicenseService.acceptVerifiedSnapshot(verified);
        client.acknowledge(verified.version(), verified.sha256());
    }

    private LicenseDocument readDocument(String payload) {
        try {
            return objectMapper.readValue(payload, LicenseDocument.class);
        } catch (JsonProcessingException exception) {
            throw new LicenseVerificationException("platform license payload is malformed");
        }
    }

    private String writeDocument(LicenseDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize verified license", exception);
        }
    }

    private static String requiredText(JsonNode object, String field) {
        String value = object.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new LicenseVerificationException("platform license response is missing " + field);
        }
        return value;
    }

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
