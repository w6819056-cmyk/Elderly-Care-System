package com.smartelder.core.licensing;

public class TenantModuleException extends IllegalStateException {
    private final String code;
    public TenantModuleException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
