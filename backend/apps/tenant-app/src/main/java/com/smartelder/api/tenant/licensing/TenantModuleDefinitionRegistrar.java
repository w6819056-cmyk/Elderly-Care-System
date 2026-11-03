package com.smartelder.api.tenant.licensing;

import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.api.ModuleRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class TenantModuleDefinitionRegistrar implements CommandLineRunner {
    private final JdbcClient jdbc;
    private final ModuleRegistry registry;
    private final java.util.Map<String,String> labels=new java.util.HashMap<>();
    public TenantModuleDefinitionRegistrar(JdbcClient jdbc, ModuleRegistry registry, java.util.List<com.smartelder.module.api.ModulePermissionLabels> providers) {
        this.jdbc = jdbc; this.registry = registry;
        for(var provider:providers) provider.permissionNames().forEach((code,name)->{if(name==null||name.isBlank())throw new IllegalArgumentException("模块权限必须提供中文名称");labels.put(code,name);});
    }
    @Override public void run(String... args) {
        for (ModuleDescriptor module : registry.all()) {
            for (String permission : module.permissionCodes()) {
                String owner = jdbc.sql("SELECT module_code FROM iam_permission_definition WHERE permission_code=:code")
                        .param("code", permission).query(String.class).optional().orElse(null);
                if (owner != null && !module.code().equals(owner))
                    throw new IllegalStateException("module permission ownership conflict: " + permission);
                String name = labels.get(permission);
                if (owner == null) {
                    boolean describedByRuntimeModule = name != null;
                    jdbc.sql("INSERT INTO iam_permission_definition(permission_code,module_code,name,description,active,version,created_at) "
                                    + "VALUES(:code,:module,:name,:description,TRUE,0,CURRENT_TIMESTAMP)")
                            .param("code", permission).param("module", module.code())
                            .param("name", describedByRuntimeModule ? name : catalogPermissionName(module))
                            .param("description", describedByRuntimeModule ? module.name()+"："+name : "模块目录权限；模块安装后由运行包提供正式权限名称")
                            .update();
                }
                if (name != null) {
                    jdbc.sql("UPDATE iam_permission_definition SET name=:name,description=:description WHERE permission_code=:code AND module_code=:module")
                        .param("name",name).param("description",module.name()+"："+name).param("code",permission).param("module",module.code()).update();
                }
            }
        }
    }

    private static String catalogPermissionName(ModuleDescriptor module) {
        return module.name() + "模块权限（待安装）";
    }
}
