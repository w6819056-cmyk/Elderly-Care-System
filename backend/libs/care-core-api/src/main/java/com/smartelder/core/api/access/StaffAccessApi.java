package com.smartelder.core.api.access;

/** Current request identity, resolved by the host. Never supplied by a request body. */
public interface StaffAccessApi { TenantAccessContext current(); }
