package com.smartelder.api.tenant.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartelder.platform.licensing.Ed25519LicenseSigner;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import com.smartelder.platform.licensing.LicenseDocument;
import com.smartelder.platform.licensing.LicenseKeyPair;
import com.smartelder.platform.licensing.LicenseModule;
import com.smartelder.platform.licensing.LicenseSignature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantLicenseVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void verifiesSignatureBindingAndReturnsDigestForExactCanonicalPayload() throws Exception {
        LicenseKeyPair keyPair = LicenseKeyPair.generate();
        LicenseDocument signed = sign(keyPair);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TenantLicenseVerifier verifier = new TenantLicenseVerifier(
                Map.of("key-1", keyPair.publicKey()), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = verifier.verify(mapper.writeValueAsString(signed),
                new TenantLicenseVerifier.ExpectedInstance("ORG-TEST", "ins-test", "sha256:fingerprint"), NOW);

        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.sha256()).isEqualTo(LicenseCanonicalizer.sha256(signed));
        assertThat(result.modules()).singleElement().extracting(
                com.smartelder.core.licensing.TenantLicenseRepository.ModuleGrant::moduleCode)
                .isEqualTo("institution");
    }

    @Test
    void rejectsUnknownKeyIdInvalidSignatureAndMismatchedEnterpriseIdentity() throws Exception {
        LicenseKeyPair keyPair = LicenseKeyPair.generate();
        LicenseDocument signed = sign(keyPair);
        TenantLicenseVerifier verifier = new TenantLicenseVerifier(
                Map.of("key-1", keyPair.publicKey()), new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var expected = new TenantLicenseVerifier.ExpectedInstance("ORG-TEST", "ins-test", "sha256:fingerprint");

        assertThatThrownBy(() -> verifier.verify(json(signed.withSignature(
                        new LicenseSignature("Ed25519", "unknown", signed.signature().value()))), expected, NOW))
                .isInstanceOf(LicenseVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(json(signed),
                        new TenantLicenseVerifier.ExpectedInstance("ORG-OTHER", "ins-test", "sha256:fingerprint"), NOW))
                .isInstanceOf(LicenseVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(json(signed.withSignature(
                        new LicenseSignature("Ed25519", "key-1", "bad-signature"))), expected, NOW))
                .isInstanceOf(LicenseVerificationException.class);
    }

    private static LicenseDocument sign(LicenseKeyPair keyPair) {
        var unsigned = new LicenseDocument(2, 3, "lic-test", "ORG-TEST", "ins-test", "sha256:fingerprint",
                NOW, NOW, null, 7, new LicenseDocument.HeartbeatPolicy(6, 30),
                List.of(new LicenseModule("institution", "FORMAL", "ACTIVE", NOW, null, true, 4)),
                new LicenseSignature("Ed25519", "key-1", null));
        return new Ed25519LicenseSigner("key-1", keyPair.privateKey()).sign(unsigned);
    }

    private static String json(LicenseDocument document) {
        try {
            return new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(document);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
