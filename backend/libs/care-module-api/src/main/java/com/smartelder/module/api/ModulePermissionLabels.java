package com.smartelder.module.api;
import java.util.Map;
/** Modules may publish Chinese permission names through a Spring bean implementing this interface. */
public interface ModulePermissionLabels { Map<String,String> permissionNames(); }
