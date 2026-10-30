package com.smartelder.module.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModuleDependency(String moduleCode, String minimumVersion) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public ModuleDependency {
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(minimumVersion, "minimumVersion must not be null");
        if (!CODE.matcher(moduleCode).matches()) {
            throw new IllegalArgumentException("Invalid module dependency code: " + moduleCode);
        }
        if (minimumVersion.isBlank()) {
            throw new IllegalArgumentException("minimumVersion must not be blank");
        }
    }
}