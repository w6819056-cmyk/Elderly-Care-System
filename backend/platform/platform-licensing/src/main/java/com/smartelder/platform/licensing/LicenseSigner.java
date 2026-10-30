package com.smartelder.platform.licensing;

public interface LicenseSigner {

    String algorithm();

    String keyId();

    String sign(String payload);
}
