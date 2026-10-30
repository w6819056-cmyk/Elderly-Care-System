package com.smartelder.platform.licensing;

public interface LicenseVerifier {

    boolean verify(LicenseDocument document);
}
