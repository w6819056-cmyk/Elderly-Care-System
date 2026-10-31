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
    public TenantModuleDefinitionRegistrar(JdbcClient jdbc, ModuleRegistry registry) {
        this.jdbc = jdbc; this.registry = registry;
    }
    @Override public void run(String... args) {
        for (ModuleDescriptor module : registry.all()) {
            for (String permission : module.permissionCodes()) {
                String owner = jdbc.sql("SELECT module_code FROM iam_permission_definition WHERE permission_code=:code")
                        .param("code", permission).query(String.class).optional().orElse(null);
                if (owner != null && !module.code().equals(owner))
                    throw new IllegalStateException("module permission ownership conflict: " + permission);
                if (owner == null) {
                    jdbc.sql("INSERT INTO iam_permission_definition(permission_code,module_code,name,description,active,version,created_at) "
                                    + "VALUES(:code,:module,:name,:description,TRUE,0,CURRENT_TIMESTAMP)")
                            .param("code", permission).param("module", module.code()).param("name", permission)
                            .param("description", "Permission registered by module " + module.code()).update();
                }
            }
        }
    }
}
