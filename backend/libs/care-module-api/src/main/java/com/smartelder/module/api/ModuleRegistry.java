package com.smartelder.module.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;

public class ModuleRegistry {
    private final Map<String, ModuleDescriptor> descriptors;
    public ModuleRegistry(Collection<ModuleDescriptor> registered) {
        Map<String, ModuleDescriptor> byCode = new LinkedHashMap<>();
        for (ModuleDescriptor descriptor : registered) {
            if (byCode.putIfAbsent(descriptor.code(), descriptor) != null)
                throw new IllegalStateException("duplicate module code: " + descriptor.code());
        }
        Map<String, String> resourceOwners = new LinkedHashMap<>();
        Map<String, String> menuOwners = new LinkedHashMap<>();
        Map<String, String> permissionOwners = new LinkedHashMap<>();
        for (ModuleDescriptor descriptor : byCode.values()) {
            for (ModuleDependency dependency : descriptor.dependencies()) {
                ModuleDescriptor installed = byCode.get(dependency.moduleCode());
                if (installed == null)
                    throw new IllegalStateException("unknown module dependency: " + dependency.moduleCode());
                if (compareVersions(installed.version(), dependency.minimumVersion()) < 0)
                    throw new IllegalStateException("module dependency version is too low: " + dependency.moduleCode());
            }
            for (ModuleResourceOperation operation : descriptor.resourceOperations()) {
                String key = descriptor.code() + "#" + operation.key();
                String prior = resourceOwners.putIfAbsent(key, descriptor.code());
                if (prior != null) throw new IllegalStateException("duplicate module resource operation: " + key);
            }
            for (ModuleMenuEntry entry : descriptor.menuEntries()) {
                String prior = menuOwners.putIfAbsent(entry.route(), descriptor.code());
                if (prior != null) throw new IllegalStateException("duplicate module menu route: " + entry.route());
            }
            for (String permission : descriptor.permissionCodes()) {
                String prior = permissionOwners.putIfAbsent(permission, descriptor.code());
                if (prior != null) throw new IllegalStateException("duplicate module permission code: " + permission);
            }
        }
        this.descriptors = Map.copyOf(byCode);
    }
    public Collection<ModuleDescriptor> all() { return descriptors.values(); }
    public ModuleDescriptor require(String code) {
        ModuleDescriptor value = descriptors.get(code);
        if (value == null) throw new IllegalArgumentException("unregistered module: " + code);
        return value;
    }
    public boolean contains(String code) { return descriptors.containsKey(code); }
    private static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            BigInteger a = new BigInteger(i < left.length ? left[i] : "0");
            BigInteger b = new BigInteger(i < right.length ? right[i] : "0");
            int compared = a.compareTo(b);
            if (compared != 0) return compared;
        }
        return 0;
    }
}
