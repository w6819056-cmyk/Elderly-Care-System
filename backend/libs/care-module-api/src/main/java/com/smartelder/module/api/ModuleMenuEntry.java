package com.smartelder.module.api;

import java.util.Objects;

public record ModuleMenuEntry(String key, String label, String route, String icon, String permissionCode) {
    public ModuleMenuEntry {
        Objects.requireNonNull(key); Objects.requireNonNull(label); Objects.requireNonNull(route);
        Objects.requireNonNull(icon); Objects.requireNonNull(permissionCode);
        if (key.isBlank() || label.isBlank() || !route.startsWith("/") || route.contains(".."))
            throw new IllegalArgumentException("invalid module menu entry");
        if (permissionCode.isBlank()) throw new IllegalArgumentException("menu permission must not be blank");
    }
}
