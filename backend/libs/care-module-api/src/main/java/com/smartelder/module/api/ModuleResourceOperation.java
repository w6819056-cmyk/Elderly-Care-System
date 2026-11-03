package com.smartelder.module.api;

import java.util.Objects;

public record ModuleResourceOperation(String resource, String operation, String permissionCode,
                                       boolean storeScoped, boolean writeOperation) {
    public ModuleResourceOperation {
        Objects.requireNonNull(resource); Objects.requireNonNull(operation); Objects.requireNonNull(permissionCode);
        if (resource.isBlank() || operation.isBlank() || permissionCode.isBlank())
            throw new IllegalArgumentException("resource operation fields must not be blank");
    }
    public ModuleResourceOperation(String resource, String operation, String permissionCode, boolean storeScoped) {
        this(resource, operation, permissionCode, storeScoped, false);
    }
    public String key() { return resource + "#" + operation; }
}
