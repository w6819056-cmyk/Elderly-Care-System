package com.smartelder.api.tenant.hq;

import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.core.licensing.TenantLicenseService;
import com.smartelder.core.licensing.TenantModuleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant/v1/hq")
public class HqModuleController {
    private final TenantModuleService modules;
    private final TenantLicenseService licenses;
    private final Clock clock = Clock.systemUTC();
    public HqModuleController(TenantModuleService modules, TenantLicenseService licenses) {
        this.modules = modules; this.licenses = licenses;
    }
    @GetMapping("/modules")
    public ApiResponse<List<?>> list(Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(modules.listModules(context(auth)), trace(request), clock);
    }
    @PutMapping("/modules/{moduleCode}/activation")
    public ApiResponse<Void> activation(@PathVariable String moduleCode, @Valid @RequestBody ActivationRequest body,
            Authentication auth, HttpServletRequest request) {
        modules.setActivation(moduleCode, body.enabled(), context(auth), trace(request));
        return ApiResponse.success(null, trace(request), clock);
    }
    @GetMapping("/modules/{moduleCode}/assignments")
    public ApiResponse<List<?>> assignments(@PathVariable String moduleCode, Authentication auth,
            HttpServletRequest request) {
        return ApiResponse.success(modules.listAssignments(moduleCode, context(auth)), trace(request), clock);
    }
    @PostMapping("/modules/{moduleCode}/assignments")
    public ApiResponse<?> assign(@PathVariable String moduleCode, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AssignmentRequest body, Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(modules.assignStore(moduleCode, body.storePublicId(), key,
                context(auth), trace(request)), trace(request), clock);
    }
    @DeleteMapping("/modules/{moduleCode}/assignments/{storePublicId}")
    public ApiResponse<Void> revoke(@PathVariable String moduleCode, @PathVariable String storePublicId,
            Authentication auth, HttpServletRequest request) {
        modules.revokeAssignment(moduleCode, storePublicId, context(auth), trace(request));
        return ApiResponse.success(null, trace(request), clock);
    }
    @GetMapping("/license/status")
    public ApiResponse<?> licenseStatus(Authentication auth, HttpServletRequest request) {
        TenantStaffPrincipal principal = (TenantStaffPrincipal) auth.getPrincipal();
        if (!"HQ".equals(principal.accessContext().staffType())
                || !principal.accessContext().permissionCodes().contains("core.module.view"))
            throw new com.smartelder.core.licensing.TenantModuleException("PERMISSION_DENIED", "missing permission: core.module.view");
        return ApiResponse.success(licenses.currentStatus(), trace(request), clock);
    }
    private static com.smartelder.core.api.access.TenantAccessContext context(Authentication auth) {
        return ((TenantStaffPrincipal) auth.getPrincipal()).accessContext();
    }
    private static String trace(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return value == null ? "tr_unknown" : value.toString();
    }
    public record ActivationRequest(boolean enabled) {}
    public record AssignmentRequest(@NotBlank String storePublicId) {}
}
