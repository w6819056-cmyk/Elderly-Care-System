package com.smartelder.module.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModuleDependency(String moduleCode, String minimumVersion) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}");

    public ModuleDependency {
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(minimumVersion, "minimumVersion must not be null");
        if (!CODE.matcher(moduleCode).matches()) {
            throw new IllegalArgumentException("Invalid module dependency code: " + moduleCode);
        }
        if (!VERSION.matcher(minimumVersion).matches()) {
            throw new IllegalArgumentException("minimumVersion must use numeric dotted form");
        }
    }
}
