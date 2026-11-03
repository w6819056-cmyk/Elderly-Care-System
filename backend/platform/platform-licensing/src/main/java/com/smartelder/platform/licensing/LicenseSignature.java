package com.smartelder.platform.licensing;

public record LicenseSignature(String algorithm, String keyId, String value) {

    public LicenseSignature {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("signature.algorithm must not be blank");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("signature.keyId must not be blank");
        }
    }
}
