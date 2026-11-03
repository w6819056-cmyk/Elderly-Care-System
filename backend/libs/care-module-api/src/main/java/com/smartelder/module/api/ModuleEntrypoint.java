package com.smartelder.module.api;

import java.util.List;
import java.util.Map;

/** Host ABI v1. Implementations are signed trusted server code, not sandboxed scripts. */
public interface ModuleEntrypoint {
    List<Class<?>> configurationClasses();
    Map<String,String> permissionNames();
}
