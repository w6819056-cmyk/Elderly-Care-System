package com.smartelder.module.api;

/** Adds a module-owned summary to an authorized headquarters customer detail. */
public interface CustomerManagementFeatureProvider {
    String moduleCode();
    String requiredPermission();
    Object get(String customerPublicId);
}
