package com.smartelder.platform.licensing;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public record LicenseKeyPair(PrivateKey privateKey, PublicKey publicKey) {

    public LicenseKeyPair {
        Objects.requireNonNull(privateKey, "privateKey must not be null");
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        if (!isEd25519(privateKey.getAlgorithm()) || !isEd25519(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("License key pair must use Ed25519");
        }
    }

    public static LicenseKeyPair generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new LicenseKeyPair(pair.getPrivate(), pair.getPublic());
        } catch (Exception exception) {
            throw new IllegalStateException("Ed25519 is unavailable", exception);
        }
    }

    public static LicenseKeyPair fromPrivateKeyPem(String privateKeyPem) {
        String privateBlock = pemBlock(privateKeyPem, "PRIVATE KEY");
        PrivateKey privateKey = PemKeys.parsePrivateKey(privateBlock);
        String publicBlock = optionalPemBlock(privateKeyPem, "PUBLIC KEY");
        if (publicBlock != null) {
            return new LicenseKeyPair(privateKey, PemKeys.parsePublicKey(publicBlock));
        }
        return new LicenseKeyPair(privateKey, derivePublicKey(privateKey));
    }

    public String privateKeyPem() {
        return toPem("PRIVATE KEY", privateKey.getEncoded());
    }

    public String publicKeyPem() {
        return toPem("PUBLIC KEY", publicKey.getEncoded());
    }

    private static PublicKey derivePublicKey(PrivateKey privateKey) {
        byte[] encoded = privateKey.getEncoded();
        if (encoded == null || encoded.length < 32) {
            throw new IllegalArgumentException("Unsupported Ed25519 private key encoding");
        }
        byte[] seed = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(255, new SeedOnlyRandom(seed));
            KeyPair reconstructed = generator.generateKeyPair();
            if (!Arrays.equals(encoded, reconstructed.getPrivate().getEncoded())) {
                throw new IllegalArgumentException("Cannot derive Ed25519 public key from private key");
            }
            return reconstructed.getPublic();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot derive Ed25519 public key from private key", exception);
        }
    }

    private static String toPem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }

    private static String pemBlock(String pem, String type) {
        String block = optionalPemBlock(pem, type);
        if (block == null) {
            throw new IllegalArgumentException("Missing " + type + " PEM block");
        }
        return block;
    }

    private static String optionalPemBlock(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM must not be blank");
        }
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = pem.indexOf(begin);
        int finish = pem.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0) {
            return null;
        }
        return pem.substring(start, finish + end.length());
    }

    private static boolean isEd25519(String algorithm) {
        return "Ed25519".equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm);
    }

    private static final class SeedOnlyRandom extends SecureRandom {

        private final byte[] seed;

        private SeedOnlyRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length > seed.length) {
                throw new IllegalArgumentException("Unexpected Ed25519 key derivation request");
            }
            System.arraycopy(seed, 0, bytes, 0, bytes.length);
        }
    }
}
