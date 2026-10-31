package com.smartelder.api.tenant.licensing;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.core.licensing.TenantLicenseService;
import com.smartelder.platform.licensing.Ed25519LicenseSigner;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import com.smartelder.platform.licensing.LicenseDocument;
import com.smartelder.platform.licensing.LicenseKeyPair;
import com.smartelder.platform.licensing.LicenseModule;
import com.smartelder.platform.licensing.LicenseSignature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import com.smartelder.core.licensing.TenantLicenseRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LicenseSyncCoordinatorTest {

    @Test
    void failedHeartbeatPreservesSnapshotAndRetryAppliesNewVersionBeforeAcknowledging() throws Exception {
        Instant now = Instant.parse("2026-10-31T00:00:00Z");
        var current = new TenantLicenseRepository.LicenseStatus("ORG-TEST", "ins-test", "fp-test", "lic-4",
                2, 4, "a".repeat(64), now, now, "ACTIVE", List.of());
        LicenseKeyPair keys = LicenseKeyPair.generate();
        var signer = new Ed25519LicenseSigner("key-1", keys.privateKey());
        var signed = signer.sign(new LicenseDocument(2, 5, "lic-5", "ORG-TEST", "ins-test", "fp-test",
                now, now, null, 7, new LicenseDocument.HeartbeatPolicy(6, 30),
                List.of(new LicenseModule("membership", "FORMAL", "ACTIVE", now, null, true, 2)),
                new LicenseSignature("Ed25519", "key-1", null)));
        var recovered = new TenantLicenseRepository.LicenseStatus("ORG-TEST", "ins-test", "fp-test", "lic-5",
                2, 5, LicenseCanonicalizer.sha256(signed), now, now, "ACTIVE", List.of());
        var client = mock(PlatformLicenseClient.class);
        var service = mock(TenantLicenseService.class);
        var properties = new TenantLicenseProperties();
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties.setOrgNo("ORG-TEST");
        properties.setInstancePublicId("ins-test");
        properties.setInstanceFingerprint("fp-test");
        String digest = LicenseCanonicalizer.sha256(signed);
        var transfer = mapper.createObjectNode()
                .put("payloadJson", LicenseCanonicalizer.canonicalize(signed))
                .put("sha256", digest)
                .put("signature", signed.signature().value())
                .put("signatureAlgorithm", "Ed25519")
                .put("keyId", "key-1");
        var heartbeat = mapper.createObjectNode().set("data", mapper.createObjectNode()
                .put("platformTime", now.toString()).put("instanceStatus", "ONLINE")
                .set("licensePayload", transfer));
        AtomicReference<TenantLicenseRepository.LicenseStatus> localSnapshot = new AtomicReference<>(current);
        when(service.currentStatus()).thenAnswer(invocation -> localSnapshot.get());
        doAnswer(invocation -> {
            localSnapshot.set(recovered);
            return null;
        }).when(service).acceptVerifiedSnapshot(org.mockito.ArgumentMatchers.any());
        when(client.heartbeat(4)).thenThrow(new ResourceAccessException("platform unavailable")).thenReturn(heartbeat);
        var verifier = new TenantLicenseVerifier(Map.of("key-1", keys.publicKey()), mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        var coordinator = new LicenseSyncCoordinator(client, service,
                verifier, properties, mapper, Clock.fixed(now, ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThatThrownBy(coordinator::syncNow)
                .isInstanceOf(ResourceAccessException.class);
        org.assertj.core.api.Assertions.assertThat(service.currentStatus()).isSameAs(current);

        coordinator.syncNow();

        org.assertj.core.api.Assertions.assertThat(service.currentStatus()).isSameAs(recovered);
        InOrder order = inOrder(service, client);
        order.verify(service).acceptVerifiedSnapshot(org.mockito.ArgumentMatchers.argThat(
                snapshot -> snapshot.version() == 5 && snapshot.sha256().equals(digest)));
        order.verify(client).acknowledge(5, digest);
    }

    @Test
    void recordsExplicitPlatformSuspensionAndPropagatesControlError() {
        var client = mock(PlatformLicenseClient.class);
        var service = mock(TenantLicenseService.class);
        var properties = new TenantLicenseProperties();
        RestClientResponseException suspended = new RestClientResponseException(
                "suspended", HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                new HttpHeaders(), "{\"code\":\"ENTERPRISE_SUSPENDED\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(client.heartbeat(0)).thenThrow(suspended);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var coordinator = new LicenseSyncCoordinator(client, service,
                new TenantLicenseVerifier(Map.of(), mapper, Clock.systemUTC()), properties, mapper, Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(coordinator::syncNow)
                .isSameAs(suspended);

        verify(service).markPlatformStatus("SUSPENDED");
    }

    @Test
    void commitsVerifiedSnapshotBeforeAcknowledgingIt() throws Exception {
        Instant now = Instant.parse("2026-10-31T00:00:00Z");
        LicenseKeyPair keys = LicenseKeyPair.generate();
        var signer = new Ed25519LicenseSigner("key-1", keys.privateKey());
        var signed = signer.sign(new LicenseDocument(2, 4, "lic-4", "ORG-TEST", "ins-test", "fp-test",
                now, now, null, 7, new LicenseDocument.HeartbeatPolicy(6, 30),
                List.of(new LicenseModule("institution", "FORMAL", "ACTIVE", now, null, true, 3)),
                new LicenseSignature("Ed25519", "key-1", null)));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var client = mock(PlatformLicenseClient.class);
        var service = mock(TenantLicenseService.class);
        var properties = new TenantLicenseProperties();
        properties.setOrgNo("ORG-TEST");
        properties.setInstancePublicId("ins-test");
        properties.setInstanceFingerprint("fp-test");
        String payload = LicenseCanonicalizer.canonicalize(signed);
        String digest = LicenseCanonicalizer.sha256(signed);
        var transfer = mapper.createObjectNode()
                .put("payloadJson", payload)
                .put("sha256", digest)
                .put("signature", signed.signature().value())
                .put("signatureAlgorithm", "Ed25519")
                .put("keyId", "key-1");
        when(client.heartbeat(0)).thenReturn(mapper.createObjectNode().set("data", mapper.createObjectNode()
                .put("platformTime", now.toString()).put("instanceStatus", "ONLINE")
                .set("licensePayload", transfer)));
        var verifier = new TenantLicenseVerifier(Map.of("key-1", keys.publicKey()), mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        var coordinator = new LicenseSyncCoordinator(client, service, verifier, properties, mapper,
                Clock.fixed(now, ZoneOffset.UTC));

        coordinator.syncNow();

        InOrder order = inOrder(service, client);
        order.verify(service).acceptVerifiedSnapshot(org.mockito.ArgumentMatchers.argThat(
                snapshot -> snapshot.version() == 4 && snapshot.sha256().equals(digest)));
        order.verify(client).acknowledge(4, digest);
    }
}
