package com.smartelder.api.tenant.portal;
import com.smartelder.module.api.CustomerFeatureProvider;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@RestController
public class CustomerFeaturesController {
 private final CustomerService customers;private final List<CustomerFeatureProvider> providers;private final JdbcClient jdbc;private final RuntimeState runtime;
 public CustomerFeaturesController(CustomerService customers,List<CustomerFeatureProvider> providers,JdbcClient jdbc,RuntimeState runtime){this.customers=customers;this.providers=List.copyOf(providers);this.jdbc=jdbc;this.runtime=runtime;
  Set<String> codes=new HashSet<>();for(var provider:providers){String code=provider.moduleCode();if(!codes.add(code)||!org.springframework.aop.support.AopUtils.getTargetClass(provider).getName().startsWith("com.smartelder.modules."+code.replace('-','_')+"."))throw new IllegalArgumentException("Customer feature must belong to its installed module");}
 }
 @GetMapping("/api/public/customer/features")
 public Object current(@RequestHeader(value="X-Customer-Token",required=false) String token,HttpServletRequest request){
  String customer=customers.me(customers.resolve(token)).publicId();List<Feature> result=new ArrayList<>();
  boolean active=jdbc.sql("SELECT COUNT(*) FROM lic_license_snapshot WHERE id=1 AND platform_instance_status='ACTIVE'").query(Long.class).single()==1;
  if(active)for(var provider:providers){String code=provider.moduleCode();
   if(runtime.loaded().stream().noneMatch(m->m.manifest().module().code().equals(code)))continue;
   boolean allowed=jdbc.sql("SELECT COUNT(*) FROM lic_module_entitlement e JOIN core_enterprise_module m ON m.module_code=e.module_code WHERE e.module_code=:code AND e.status='ACTIVE' AND e.starts_at<=:now AND m.enabled=TRUE").param("code",code).param("now",Timestamp.from(Instant.now())).query(Long.class).single()==1;
   // Read-only access to one's existing module data remains available after expiration.
   if(allowed)result.add(new Feature(code,provider.get(customer)));
  }
  return ApiResponse.success(result,String.valueOf(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());
 }
 public record Feature(String moduleCode,Object data){}
}
