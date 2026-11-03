package com.smartelder.core.licensing;

public record ModuleAccessDecision(String state, boolean allowed, String reasonCode, long licenseVersion) {}
