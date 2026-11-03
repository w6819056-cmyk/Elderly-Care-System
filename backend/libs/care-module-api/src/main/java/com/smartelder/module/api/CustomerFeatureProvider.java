package com.smartelder.module.api;

/** Read-only consumer feature; the host supplies the authenticated enterprise customer ID. */
public interface CustomerFeatureProvider {
 String moduleCode();
 Object get(String customerPublicId);
}
