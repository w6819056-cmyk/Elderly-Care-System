package com.smartelder.platform.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Ed25519LicenseSignerTest {

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final String PAYLOAD = """
            {"schemaVersion":1,"licenseVersion":1,"status":"ACTIVE","modules":[]}
            """;

    @Test
    void signsPayloadThatCanBeVerifiedWithRegisteredPublicKey() {
        Ed25519LicenseSigner signer = new Ed25519LicenseSigner(
                "platform-license-test",
                KEY_PAIR.getPrivate()
        );

        String signature = signer.sign(PAYLOAD);

        assertThat(signer.algorithm()).isEqualTo("Ed25519");
        assertThat(signer.keyId()).isEqualTo("platform-license-test");
        assertThat(Ed25519LicenseVerifier.verify(
                PAYLOAD,
                signature,
                publicKeyPem(KEY_PAIR)
        )).isTrue();
    }

    @Test
    void rejectsTamperedPayloadAndDifferentPublicKey() {
        Ed25519LicenseSigner signer = new Ed25519LicenseSigner(
                "platform-license-test",
                KEY_PAIR.getPrivate()
        );
        String signature = signer.sign(PAYLOAD);

        assertThat(Ed25519LicenseVerifier.verify(
                PAYLOAD.replace("ACTIVE", "SUSPENDED"),
                signature,
                publicKeyPem(KEY_PAIR)
        )).isFalse();
        assertThat(Ed25519LicenseVerifier.verify(
                PAYLOAD,
                signature,
                publicKeyPem(generateKeyPair())
        )).isFalse();
    }

    @Test
    void returnsFalseForMalformedSignature() {
        assertThat(Ed25519LicenseVerifier.verify(
                PAYLOAD,
                "not-base64",
                publicKeyPem(KEY_PAIR)
        )).isFalse();
    }

    @Test
    void acceptsEdDsaAlgorithmNameReportedBySomeJavaProviders() {
        PrivateKey privateKey = mock(PrivateKey.class);
        when(privateKey.getAlgorithm()).thenReturn("EdDSA");

        assertThatCode(() -> new Ed25519LicenseSigner("platform-license-test", privateKey))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonEd25519PrivateKey() {
        PrivateKey privateKey = mock(PrivateKey.class);
        when(privateKey.getAlgorithm()).thenReturn("RSA");

        assertThatThrownBy(() -> new Ed25519LicenseSigner("platform-license-test", privateKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ed25519");
    }

    @Test
    void rejectsMalformedPem() {
        assertThatThrownBy(() -> Ed25519LicenseSigner.fromPem(
                "platform-license-test",
                "not-a-private-key"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String publicKeyPem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(
                64,
                "\n".getBytes(StandardCharsets.US_ASCII)
        ).encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n"
                + encoded
                + "\n-----END PUBLIC KEY-----\n";
    }
}
