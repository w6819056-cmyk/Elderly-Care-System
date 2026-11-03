package com.smartelder.api.tenant.hq;

import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.core.organization.TenantOrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant/v1/hq")
public class HqOrganizationController {

    private final TenantOrganizationService service;
    private final Clock clock = Clock.systemUTC();

    public HqOrganizationController(TenantOrganizationService service) { this.service = service; }

    @GetMapping("/enterprise")
    public ApiResponse<TenantOrganizationService.EnterpriseView> getEnterprise(
            Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(service.getEnterprise(context(auth)), traceId(request), clock);
    }

    @PutMapping("/enterprise")
    public ApiResponse<TenantOrganizationService.EnterpriseView> updateEnterprise(
            @Valid @RequestBody UpdateEnterpriseRequest body, Authentication auth, HttpServletRequest request) {
        var command = new TenantOrganizationService.UpdateEnterpriseCommand(
                body.name(), body.legalName(), body.contactName(), body.contactPhone());
        return ApiResponse.success(service.updateEnterprise(command, context(auth), traceId(request)), traceId(request), clock);
    }

    @GetMapping("/stores")
    public ApiResponse<List<TenantOrganizationService.StoreView>> listStores(
            Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(service.listStores(context(auth)), traceId(request), clock);
    }

    @PostMapping("/stores")
    public ApiResponse<TenantOrganizationService.StoreView> createStore(
            @Valid @RequestBody CreateStoreRequest body, Authentication auth, HttpServletRequest request) {
        var command = new TenantOrganizationService.CreateStoreCommand(
                body.storeNo(), body.name(), body.regionCode(), body.address());
        return ApiResponse.success(service.createStore(command, context(auth), traceId(request)), traceId(request), clock);
    }

    @PatchMapping("/stores/{publicId}/status")
    public ApiResponse<TenantOrganizationService.StoreView> changeStoreStatus(
            @PathVariable String publicId, @Valid @RequestBody ChangeStoreStatusRequest body,
            Authentication auth, HttpServletRequest request) {
        return ApiResponse.success(service.changeStoreStatus(publicId, body.status(), context(auth), traceId(request)),
                traceId(request), clock);
    }

    private static com.smartelder.core.api.access.TenantAccessContext context(Authentication auth) {
        return ((TenantStaffPrincipal) auth.getPrincipal()).accessContext();
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return value == null ? "tr_unknown" : value.toString();
    }

    public record UpdateEnterpriseRequest(@NotBlank String name, String legalName, String contactName, String contactPhone) {}
    public record CreateStoreRequest(@NotBlank String storeNo, @NotBlank String name, String regionCode, String address) {}
    public record ChangeStoreStatusRequest(@NotBlank String status) {}
}
