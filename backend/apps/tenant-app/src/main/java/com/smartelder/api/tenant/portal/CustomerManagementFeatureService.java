package com.smartelder.api.tenant.portal;

import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.module.api.CustomerManagementFeatureProvider;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CustomerManagementFeatureService {
    private final CustomerManagementService customers;
    private final List<CustomerManagementFeatureProvider> providers;
    private final JdbcClient jdbc;
    private final RuntimeState runtime;
    private final Clock clock;

    public CustomerManagementFeatureService(CustomerManagementService customers,
            List<CustomerManagementFeatureProvider> providers,JdbcClient jdbc,RuntimeState runtime,Clock clock) {
        this.customers=customers;this.providers=List.copyOf(providers);this.jdbc=jdbc;this.runtime=runtime;this.clock=clock;
        Set<String> codes=new HashSet<>();
        for(var provider:providers) {
            String code=provider.moduleCode();
            if(code==null||!codes.add(code)||!AopUtils.getTargetClass(provider).getName()
                    .startsWith("com.smartelder.modules."+code.replace('-','_')+"."))
                throw new IllegalArgumentException("Customer management feature must belong to its module");
            String permission=provider.requiredPermission();
            if(permission==null||permission.isBlank())
                throw new IllegalArgumentException("Customer management feature permission is required");
            boolean declared=runtime.loaded().stream().filter(m->m.manifest().module().code().equals(code))
                    .anyMatch(m->m.manifest().module().permissionCodes().contains(permission));
            if(!declared)throw new IllegalArgumentException("Customer management feature permission must be declared by its module");
        }
    }

    public List<Feature> features(TenantAccessContext actor,String customerPublicId) {
        if(actor==null||!"HQ".equals(actor.staffType()))
            throw new TenantStaffAuthException(403,"PERMISSION_DENIED","仅企业总部可查看客户模块摘要");
        customers.requireVisible(actor,customerPublicId);
        if(!platformActive()) return List.of();
        var loaded=runtime.loaded().stream().map(m->m.manifest().module().code()).collect(java.util.stream.Collectors.toSet());
        List<Feature> result=new ArrayList<>();
        for(var provider:providers) {
            String code=provider.moduleCode();
            if(!loaded.contains(code)||!actor.permissionCodes().contains(provider.requiredPermission())||!moduleActive(code))continue;
            try { result.add(new Feature(code,provider.get(customerPublicId))); }
            catch(RuntimeException ignored) { /* One optional module must not break base customer data. */ }
        }
        return List.copyOf(result);
    }

    private boolean platformActive() {
        return jdbc.sql("SELECT COUNT(*) FROM lic_license_snapshot WHERE id=1 AND platform_instance_status='ACTIVE'")
                .query(Long.class).single()==1;
    }
    private boolean moduleActive(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM lic_module_entitlement e JOIN core_enterprise_module m ON m.module_code=e.module_code "
                        +"WHERE e.module_code=:code AND e.status='ACTIVE' AND e.starts_at<=:now "
                        +"AND (e.expires_at IS NULL OR e.expires_at>:now) AND m.enabled=TRUE")
                .param("code",code).param("now",Timestamp.from(clock.instant())).query(Long.class).single()==1;
    }
    public record Feature(String moduleCode,Object data) {}
}
