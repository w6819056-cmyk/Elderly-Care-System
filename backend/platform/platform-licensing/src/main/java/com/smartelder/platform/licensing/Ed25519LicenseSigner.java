package com.smartelder.platform.licensing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

public final class Ed25519LicenseSigner implements LicenseSigner {

    private final String keyId;
    private final PrivateKey privateKey;

    public Ed25519LicenseSigner(String keyId, PrivateKey privateKey) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        this.keyId = keyId;
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null");
        if (!isEd25519KeyAlgorithm(privateKey.getAlgorithm())) {
            throw new IllegalArgumentException("privateKey must be Ed25519");
        }
    }

    public static Ed25519LicenseSigner fromPem(String keyId, String privateKeyPem) {
        return new Ed25519LicenseSigner(keyId, PemKeys.parsePrivateKey(privateKeyPem));
    }

    private static boolean isEd25519KeyAlgorithm(String algorithm) {
        return "Ed25519".equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm);
    }

    @Override
    public String algorithm() {
        return "Ed25519";
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public LicenseDocument sign(LicenseDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        LicenseDocument unsigned = document.withSignature(
                new LicenseSignature(algorithm(), keyId, null)
        );
        String payload = LicenseCanonicalizer.canonicalize(unsigned);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload.getBytes(StandardCharsets.UTF_8));
            String value = Base64.getEncoder().encodeToString(signer.sign());
            return document.withSignature(new LicenseSignature(algorithm(), keyId, value));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to sign license payload", exception);
        }
    }
}
