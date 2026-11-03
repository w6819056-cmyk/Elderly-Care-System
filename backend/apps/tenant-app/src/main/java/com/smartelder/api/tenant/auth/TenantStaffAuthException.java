package com.smartelder.api.tenant.auth;

public class TenantStaffAuthException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public TenantStaffAuthException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
}
