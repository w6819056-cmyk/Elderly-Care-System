package com.smartelder.api.tenant.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartelder.platform.licensing.LicenseKeyPair;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InstanceRequestSignerTest {

    @Test
    void signsMethodPathTimestampNonceAndExactBodyDigest() throws Exception {
        LicenseKeyPair pair = LicenseKeyPair.generate();
        InstanceRequestSigner signer = new InstanceRequestSigner(
                pair.privateKey(), Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC));
        String body = "{\"version\":3}";

        var headers = signer.sign("POST", "/api/platform/v1/instances/heartbeat", body);
        String canonical = "POST\n/api/platform/v1/instances/heartbeat\n"
                + headers.timestamp() + "\n" + headers.nonce() + "\n" + headers.bodySha256();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.publicKey());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(java.util.Base64.getDecoder().decode(headers.signature()))).isTrue();
        assertThat(headers.bodySha256()).hasSize(64);
    }
}
