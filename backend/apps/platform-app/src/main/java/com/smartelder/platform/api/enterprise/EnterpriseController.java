package com.smartelder.platform.api.enterprise;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.enterprise.EnterpriseService;
import com.smartelder.platform.core.enterprise.EnterpriseEntitlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/enterprises")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;
    private final Clock clock;
    private final EnterpriseEntitlementService entitlementService;

    public EnterpriseController(EnterpriseService enterpriseService, Clock clock, EnterpriseEntitlementService entitlementService) {
        this.enterpriseService = enterpriseService;
        this.clock = clock;
        this.entitlementService = entitlementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.enterprise.view')")
    public ApiResponse<EnterpriseService.PageResult<EnterpriseService.EnterpriseView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false)
            @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED", message = "状态值无效")
            String status,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                enterpriseService.list(page, pageSize, status),
                traceId,
                clock
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.enterprise.manage')")
    public ApiResponse<EnterpriseService.EnterpriseView> create(
            @Valid @RequestBody CreateEnterpriseRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                enterpriseService.create(
                        new EnterpriseService.CreateEnterpriseCommand(
                                request.name(),
                                null,
                                request.contactName(),
                                request.contactPhone()
                        ),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    @PostMapping("/register-existing")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.enterprise.manage')")
    public ApiResponse<EnterpriseService.EnterpriseView> registerExisting(
            @Valid @RequestBody RegisterExistingEnterpriseRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                enterpriseService.create(
                        new EnterpriseService.CreateEnterpriseCommand(
                                request.name(),
                                request.orgNo(),
                                request.contactName(),
                                request.contactPhone()
                        ),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    @PatchMapping("/{publicId}/status")
    @PreAuthorize("hasAuthority('platform.enterprise.manage')")
    public ApiResponse<EnterpriseService.EnterpriseView> changeStatus(
            @PathVariable String publicId,
            @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                enterpriseService.changeStatus(publicId, request.status(), principal, traceId),
                traceId,
                clock
        );
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('platform.enterprise.manage')")
    public ApiResponse<EnterpriseService.EnterpriseView> updateProfile(
            @PathVariable String publicId,
            @Valid @RequestBody UpdateEnterpriseRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(enterpriseService.updateProfile(publicId,
                new EnterpriseService.UpdateEnterpriseCommand(request.name(), request.contactName(), request.contactPhone()),
                principal, traceId), traceId, clock);
    }

    @GetMapping("/{publicId}/modules")
    @PreAuthorize("hasAuthority('platform.enterprise.view')")
    public ApiResponse<List<EnterpriseEntitlementService.EntitlementView>> modules(@PathVariable String publicId,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId) {
        return ApiResponse.success(entitlementService.list(publicId), traceId, clock);
    }

    @PatchMapping("/{publicId}/modules/{entitlementId}")
    @PreAuthorize("hasAuthority('platform.enterprise.manage')")
    public ApiResponse<EnterpriseEntitlementService.EntitlementView> updateModule(@PathVariable String publicId,
            @PathVariable long entitlementId, @Valid @RequestBody UpdateModuleRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal, @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId) {
        return ApiResponse.success(entitlementService.update(publicId, entitlementId,
                new EnterpriseEntitlementService.UpdateCommand(request.expiresAt(), request.storeQuota(), request.status()), principal, traceId), traceId, clock);
    }

    public record CreateEnterpriseRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 40) String contactPhone
    ) {
    }

    public record RegisterExistingEnterpriseRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,39}") String orgNo,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 40) String contactPhone
    ) {
    }

    public record ChangeStatusRequest(
            @NotBlank
            @Pattern(
                    regexp = "ACTIVE|SUSPENDED|CLOSED",
                    message = "必须为 ACTIVE、SUSPENDED 或 CLOSED"
            )
            String status
    ) {
    }

    public record UpdateEnterpriseRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 40) String contactPhone
    ) {}

    public record UpdateModuleRequest(Instant expiresAt, @Min(1) int storeQuota,
            @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}
}
