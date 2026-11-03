package com.smartelder.api.tenant.hq;

import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
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
    private final RuntimeState runtime;
    private final Clock clock = Clock.systemUTC();
    public HqModuleController(TenantModuleService modules, TenantLicenseService licenses, RuntimeState runtime) {
        this.modules = modules; this.licenses = licenses; this.runtime = runtime;
    }
    @GetMapping("/modules")
    public ApiResponse<List<?>> list(Authentication auth, HttpServletRequest request) {
        var installed = runtime.loaded().stream().collect(java.util.stream.Collectors.toMap(
                module -> module.manifest().module().code(),
                module -> module.manifest().module().version()));
        List<ModuleView> result = modules.listModules(context(auth)).stream()
                .map(module -> new ModuleView(module, installed.get(module.moduleCode())))
                .toList();
        return ApiResponse.success(result, trace(request), clock);
    }
    @GetMapping("/module-stores")
    public ApiResponse<List<?>> assignableStores(Authentication auth, HttpServletRequest request) {
        List<StoreOption> stores = modules.listAssignableStores(context(auth)).stream()
                .map(store -> new StoreOption(store.publicId(), store.storeNo(), store.name(), store.status())).toList();
        return ApiResponse.success(stores, trace(request), clock);
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
        modules.listModules(context(auth));
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
    public record StoreOption(String publicId, String storeNo, String name, String status) {}
    public record ModuleView(String moduleCode, String licenseType, String licenseStatus,
            java.time.Instant startsAt, java.time.Instant expiresAt, boolean perpetual,
            long licenseVersion, int storeQuota, int assignedStores, boolean enabled,
            String effectiveState, boolean installed, String installedVersion) {
        private ModuleView(com.smartelder.core.licensing.TenantModuleRepository.ModuleSummary module,
                String installedVersion) {
            this(module.moduleCode(), module.licenseType(), module.licenseStatus(), module.startsAt(),
                    module.expiresAt(), module.perpetual(), module.licenseVersion(), module.storeQuota(),
                    module.assignedStores(), module.enabled(), module.effectiveState(), installedVersion != null,
                    installedVersion);
        }
    }
}
