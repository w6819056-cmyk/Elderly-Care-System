package com.smartelder.api.tenant.installation;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.*;
import com.smartelder.core.api.access.TenantAccessContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/tenant/v1/module-install")
public class ModuleInstallController {
 private final ModuleInstallService service;public ModuleInstallController(ModuleInstallService service){this.service=service;}
 private TenantAccessContext actor(Authentication a){return ((TenantStaffPrincipal)a.getPrincipal()).accessContext();}
 private Object ok(Object v,HttpServletRequest r){return ApiResponse.success(v,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());}
 @GetMapping("/configuration") public Object configuration(Authentication a,HttpServletRequest r){return ok(service.configuration(actor(a)),r);}
 @GetMapping("/available") public Object available(Authentication a,HttpServletRequest r){return ok(service.available(actor(a)),r);}
 @GetMapping("/plans") public Object plans(Authentication a,HttpServletRequest r){return ok(service.plans(actor(a)),r);}
 @PostMapping("/prepare") public Object prepare(@Valid @RequestBody Prepare b,Authentication a,HttpServletRequest r){return ok(service.prepare(b.moduleCode(),b.version(),actor(a),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME))),r);}
 @PostMapping("/plans/{id}/{action}") public Object change(@PathVariable String id,@PathVariable String action,@Valid @RequestBody Change b,Authentication a,HttpServletRequest r){if(!action.equals("confirm")&&!action.equals("cancel"))throw new IllegalArgumentException("未知操作");service.change(id,b.sha256(),action.equals("cancel"),actor(a),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)));return ok(null,r);}
 public record Prepare(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{1,31}") String moduleCode,@NotBlank @Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") String version){}
 public record Change(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256){}
}
