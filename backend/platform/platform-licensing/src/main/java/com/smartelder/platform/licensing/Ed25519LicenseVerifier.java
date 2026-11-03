package com.smartelder.platform.licensing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

public final class Ed25519LicenseVerifier implements LicenseVerifier {

    private final String trustedKeyId;
    private final PublicKey publicKey;

    public Ed25519LicenseVerifier(PublicKey publicKey) {
        this(null, publicKey);
    }

    public Ed25519LicenseVerifier(String trustedKeyId, PublicKey publicKey) {
        if (trustedKeyId != null && trustedKeyId.isBlank()) {
            throw new IllegalArgumentException("trustedKeyId must not be blank");
        }
        this.trustedKeyId = trustedKeyId;
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey must not be null");
        if (!isEd25519KeyAlgorithm(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("publicKey must be Ed25519");
        }
    }

    public static PublicKey publicKeyFromPem(String publicKeyPem) {
        return PemKeys.parsePublicKey(publicKeyPem);
    }

    public static boolean verify(String payload, String signature, String publicKeyPem) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        PublicKey publicKey = publicKeyFromPem(publicKeyPem);
        return verify(payload, signature, publicKey);
    }

    public static boolean verify(String payload, String signature, PublicKey publicKey) {
        Objects.requireNonNull(payload, "payload must not be null");
        return verifyBytes(
                payload.getBytes(StandardCharsets.UTF_8),
                signature,
                publicKey
        );
    }

    public static boolean verifyBytes(byte[] payload, String signature, PublicKey publicKey) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        if (!isEd25519KeyAlgorithm(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("publicKey must be Ed25519");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public boolean verify(LicenseDocument document) {
        if (document == null || document.signature() == null || document.signature().value() == null) {
            return false;
        }
        LicenseSignature signature = document.signature();
        if (!"Ed25519".equalsIgnoreCase(signature.algorithm())) {
            return false;
        }
        if (trustedKeyId != null && !trustedKeyId.equals(signature.keyId())) {
            return false;
        }
        try {
            String payload = LicenseCanonicalizer.canonicalize(document);
            return verify(payload, signature.value(), publicKey);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isEd25519KeyAlgorithm(String algorithm) {
        return "Ed25519".equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm);
    }
}
