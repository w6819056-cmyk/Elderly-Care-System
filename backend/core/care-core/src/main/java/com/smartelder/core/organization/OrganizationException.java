package com.smartelder.core.organization;

public class OrganizationException extends IllegalStateException {

    private final String code;

    public OrganizationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
