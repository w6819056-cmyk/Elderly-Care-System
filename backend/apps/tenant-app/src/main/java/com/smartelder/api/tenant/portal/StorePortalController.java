package com.smartelder.api.tenant.portal;
import com.smartelder.common.ApiResponse;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class StorePortalController {
 private final StorePortalService service;private final Clock clock=Clock.systemUTC();
 public StorePortalController(StorePortalService service){this.service=service;}
 private <T> ApiResponse<T> ok(T data,HttpServletRequest r){Object t=r.getAttribute("traceId");return ApiResponse.success(data,t==null?"tr_portal":t.toString(),clock);}
 @GetMapping("/api/public/store-portals/{no}") public Object page(@PathVariable String no,HttpServletRequest r){return ok(service.page(no),r);}
 @PostMapping("/api/public/store-portals/{no}/login") public Object login(@PathVariable String no,@Valid @RequestBody Login b,HttpServletRequest r){service.throttle("login:"+r.getRemoteAddr());return ok(service.login(no,b.account(),b.password()),r);}
 @PostMapping("/api/public/store-portals/{no}/applications") public Object apply(@PathVariable String no,@Valid @RequestBody Apply b,HttpServletRequest r){service.throttle("apply:"+r.getRemoteAddr());String id=service.apply(no,b.username(),b.password(),b.displayName(),b.phone(),b.email());return ok(Map.of("applicationNo",id,"message","申请已受理，请等待总部审核"),r);}
 @GetMapping("/api/tenant/v1/hq/staff-applications") public Object list(Authentication auth,HttpServletRequest r){return ok(service.list(((TenantStaffPrincipal)auth.getPrincipal()).accessContext()),r);}
 @PostMapping("/api/tenant/v1/hq/staff-applications/{id}/review") public Object review(@PathVariable String id,@Valid @RequestBody Review b,Authentication auth,HttpServletRequest r){if(!Set.of("APPROVE","REJECT").contains(b.decision()))throw new IllegalArgumentException("审核决定无效");service.review(id,"APPROVE".equals(b.decision()),b.roleCodes()==null?Set.of():b.roleCodes(),b.note(),((TenantStaffPrincipal)auth.getPrincipal()).accessContext(),String.valueOf(r.getAttribute("traceId")));return ok(null,r);}
 public record Login(@NotBlank @Size(max=80) String account,@NotBlank @Size(max=200) String password){}
 public record Apply(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,80}") String username,@NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=100) String displayName,@NotBlank @Size(max=32) String phone,@Email @Size(max=254) String email){}
 public record Review(@NotBlank String decision,Set<String> roleCodes,@Size(max=500) String note){}
}
