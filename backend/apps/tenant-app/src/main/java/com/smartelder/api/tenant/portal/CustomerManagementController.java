package com.smartelder.api.tenant.portal;

import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenant/v1/customers")
public class CustomerManagementController {
    private final CustomerManagementService service;
    public CustomerManagementController(CustomerManagementService service) {this.service=service;}
    private <T> ApiResponse<T> ok(T value,HttpServletRequest request) {
        return ApiResponse.success(value,String.valueOf(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());
    }
    @GetMapping public Object list(Authentication auth,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        return ok(service.list(((TenantStaffPrincipal)auth.getPrincipal()).accessContext(),q,page,size),request);
    }
    @GetMapping("/{id}") public Object detail(Authentication auth,@PathVariable String id,HttpServletRequest request) {
        return ok(service.detail(((TenantStaffPrincipal)auth.getPrincipal()).accessContext(),id),request);
    }
}
