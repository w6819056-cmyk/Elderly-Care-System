package com.smartelder.api.tenant.licensing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public class InstanceRequestSigner {

    private final PrivateKey privateKey;
    private final Clock clock;

    public InstanceRequestSigner(PrivateKey privateKey, Clock clock) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SignedHeaders sign(String method, String path, String body) {
        String timestamp = Instant.now(clock).toString();
        String nonce = UUID.randomUUID().toString();
        String bodySha256 = sha256(body == null ? "" : body);
        String canonical = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            return new SignedHeaders(timestamp, nonce, bodySha256,
                    java.util.Base64.getEncoder().encodeToString(signer.sign()));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign platform request", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record SignedHeaders(String timestamp, String nonce, String bodySha256, String signature) {
    }
}
