package com.smartelder.api.tenant.hq;
import com.smartelder.module.api.ModuleRegistry;
import com.smartelder.core.iam.TenantIamService;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class PermissionModuleController {
 private final ModuleRegistry registry;private final TenantIamService iam;
 public PermissionModuleController(ModuleRegistry registry,TenantIamService iam){this.registry=registry;this.iam=iam;}
 @GetMapping("/api/tenant/v1/hq/permission-modules") public Object modules(Authentication auth,HttpServletRequest r){iam.listPermissionDefinitions(((TenantStaffPrincipal)auth.getPrincipal()).accessContext());var result=new ArrayList<ModuleView>();result.add(new ModuleView("core","基础经营"));result.add(new ModuleView("iam","组织与权限"));registry.all().forEach(m->result.add(new ModuleView(m.code(),m.name())));return ApiResponse.success(result,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());}
 public record ModuleView(String code,String name){}
}
