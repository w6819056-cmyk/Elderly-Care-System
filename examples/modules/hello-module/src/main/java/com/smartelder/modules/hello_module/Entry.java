package com.smartelder.modules.hello_module;
import com.smartelder.module.api.ModuleEntrypoint;
import java.util.*;
public final class Entry implements ModuleEntrypoint {
 public List<Class<?>> configurationClasses(){return List.of(HelloConfiguration.class);}
 public Map<String,String> permissionNames(){return Map.of("hello-module:record:read","查看安装验证模块");}
}
