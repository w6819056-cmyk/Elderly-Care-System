package com.smartelder.platform.licensing;

public interface LicenseSigner {

    String algorithm();

    String keyId();

    LicenseDocument sign(LicenseDocument document);
}
