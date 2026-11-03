package com.smartelder.api.tenant.portal;
import com.smartelder.common.ApiResponse;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class PublicSiteController {
 private final PublicSiteService service;
 public PublicSiteController(PublicSiteService service){this.service=service;}
 private <T> ApiResponse<T> ok(T v){return ApiResponse.success(v,"site-content",Clock.systemUTC());}
 @GetMapping("/api/tenant/v1/site-content-store") public Object ownStore(Authentication auth){return ok(service.ownStore(((TenantStaffPrincipal)auth.getPrincipal()).accessContext()));}
 @GetMapping("/api/public/site/brand") public Object brand(){return ok(service.brand());}
 @GetMapping("/api/public/site/stores") public Object stores(){return ok(service.stores());}
 @GetMapping("/api/public/site/stores/{no}") public Object store(@PathVariable String no){return ok(service.store(no));}
 @GetMapping("/api/tenant/v1/site-content/{target}") public Object get(@PathVariable String target,Authentication auth){return ok(service.editor(target,((TenantStaffPrincipal)auth.getPrincipal()).accessContext()));}
 @PutMapping("/api/tenant/v1/site-content/{target}") public Object save(@PathVariable String target,@RequestBody PublicSiteService.Content body,Authentication auth){service.save(target,body,((TenantStaffPrincipal)auth.getPrincipal()).accessContext());return ok(null);}
 @PostMapping("/api/tenant/v1/site-content/{target}/{action}") public Object action(@PathVariable String target,@PathVariable String action,@RequestBody(required=false) Map<String,String> body,Authentication auth){var actor=((TenantStaffPrincipal)auth.getPrincipal()).accessContext();switch(action){case "publish"->service.publish(target,actor);case "submit"->service.submit(target,actor);case "approve","reject"->service.review(target,"approve".equals(action),body==null?"":body.getOrDefault("note",""),body==null?"":body.getOrDefault("reviewToken",""),actor);default->throw new IllegalArgumentException("未知内容操作");}return ok(null);}
}
