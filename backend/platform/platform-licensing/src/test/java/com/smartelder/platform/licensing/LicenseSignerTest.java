package com.smartelder.platform.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LicenseSignerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2027-10-30T00:00:00Z");

    @Test
    void signsAndVerifiesCanonicalPayload() {
        LicenseKeyPair keyPair = LicenseKeyPair.generate();
        LicenseSigner signer = new Ed25519LicenseSigner("license-key-test", keyPair.privateKey());
        LicenseVerifier verifier = new Ed25519LicenseVerifier("license-key-test", keyPair.publicKey());

        LicenseDocument signed = signer.sign(document());

        assertThat(signed.signature()).isNotNull();
        assertThat(signed.signature().algorithm()).isEqualTo("Ed25519");
        assertThat(signed.signature().keyId()).isEqualTo("license-key-test");
        assertThat(signed.signature().value()).isNotBlank();
        assertThat(verifier.verify(signed)).isTrue();
    }

    @Test
    void rejectsAnyPayloadMutation() {
        LicenseKeyPair keyPair = LicenseKeyPair.generate();
        LicenseSigner signer = new Ed25519LicenseSigner("license-key-test", keyPair.privateKey());
        LicenseVerifier verifier = new Ed25519LicenseVerifier("license-key-test", keyPair.publicKey());
        LicenseDocument signed = signer.sign(document());

        assertThat(verifier.verify(signed.withOrgNo("ORG-CHANGED"))).isFalse();
        assertThat(verifier.verify(signed.withVersion(signed.version() + 1))).isFalse();
        assertThat(verifier.verify(signed.withInstanceFingerprint("sha256:changed"))).isFalse();
        assertThat(verifier.verify(signed.withModules(List.of(new LicenseModule(
                "institution",
                "FORMAL",
                "ACTIVE",
                ISSUED_AT,
                EXPIRES_AT,
                false,
                99
        ))))).isFalse();
    }

    @Test
    void canonicalDigestIsStableAndIncludesSignedVersion() {
        LicenseKeyPair keyPair = LicenseKeyPair.generate();
        LicenseDocument signed = new Ed25519LicenseSigner(
                "license-key-test", keyPair.privateKey()).sign(document().withVersion(7));

        assertThat(LicenseCanonicalizer.sha256(signed))
                .isEqualTo(LicenseCanonicalizer.sha256(signed.withSignature(
                        new LicenseSignature("Ed25519", "license-key-test", "different-value"))));
        assertThat(LicenseCanonicalizer.sha256(signed.withVersion(8)))
                .isNotEqualTo(LicenseCanonicalizer.sha256(signed));
        assertThat(LicenseCanonicalizer.sha256(signed)).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsDuplicateModuleCodesAndInvalidModuleTimeRange() {
        LicenseModule module = document().modules().get(0);
        List<LicenseModule> duplicateModules = new ArrayList<>(List.of(module, module));

        assertThatThrownBy(() -> document().withModules(duplicateModules))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new LicenseModule(
                "institution", "FORMAL", "ACTIVE", EXPIRES_AT, ISSUED_AT, false, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDifferentPublicKeyAndKeyId() {
        LicenseKeyPair signingKey = LicenseKeyPair.generate();
        LicenseKeyPair otherKey = LicenseKeyPair.generate();
        LicenseDocument signed = new Ed25519LicenseSigner(
                "license-key-test",
                signingKey.privateKey()
        ).sign(document());

        assertThat(new Ed25519LicenseVerifier(otherKey.publicKey()).verify(signed)).isFalse();
        assertThat(new Ed25519LicenseVerifier("another-key", signingKey.publicKey()).verify(signed))
                .isFalse();
    }

    @Test
    void rejectsMalformedSignature() {
        LicenseKeyPair keyPair = LicenseKeyPair.generate();
        LicenseDocument signed = new Ed25519LicenseSigner(
                "license-key-test",
                keyPair.privateKey()
        ).sign(document());
        LicenseSignature malformed = new LicenseSignature("Ed25519", "license-key-test", "not-base64");

        assertThat(new Ed25519LicenseVerifier(keyPair.publicKey()).verify(
                signed.withSignature(malformed)
        )).isFalse();
    }

    @Test
    void signsFromPemPublicKeyAndPrivateKey() {
        LicenseKeyPair generated = LicenseKeyPair.generate();
        LicenseKeyPair loaded = LicenseKeyPair.fromPrivateKeyPem(generated.privateKeyPem());
        LicenseDocument signed = new Ed25519LicenseSigner(
                "license-key-test",
                loaded.privateKey()
        ).sign(document());

        assertThat(loaded.publicKeyPem()).isEqualTo(generated.publicKeyPem());
        assertThat(new Ed25519LicenseVerifier(
                "license-key-test",
                loaded.publicKey()
        ).verify(signed)).isTrue();
    }

    private static LicenseDocument document() {
        return new LicenseDocument(
                2,
                1,
                "lic_test",
                "ORG-2026-0001",
                "ins_test",
                "sha256:fingerprint",
                ISSUED_AT,
                ISSUED_AT,
                EXPIRES_AT,
                7,
                new LicenseDocument.HeartbeatPolicy(6, 30),
                List.of(new LicenseModule(
                        "institution",
                        "FORMAL",
                        "ACTIVE",
                        ISSUED_AT,
                        EXPIRES_AT,
                        false,
                        20
                )),
                null
        );
    }
}
