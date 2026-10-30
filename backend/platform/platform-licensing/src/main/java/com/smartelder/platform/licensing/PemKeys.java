package com.smartelder.platform.licensing;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class PemKeys {

    private PemKeys() {
    }

    static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] encoded = decode(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Ed25519 private key PEM", exception);
        }
    }

    static PublicKey parsePublicKey(String pem) {
        try {
            byte[] encoded = decode(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Ed25519 public key PEM", exception);
        }
    }

    private static byte[] decode(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM must not be blank");
        }
        String normalized = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized.getBytes(StandardCharsets.US_ASCII));
    }
}
