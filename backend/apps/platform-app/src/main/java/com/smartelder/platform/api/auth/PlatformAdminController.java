package com.smartelder.platform.api.auth;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformAdminService;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/admins")
public class PlatformAdminController {

    private final PlatformAdminService adminService;
    private final Clock clock;

    public PlatformAdminController(PlatformAdminService adminService, Clock clock) {
        this.adminService = adminService;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.account.manage')")
    public ApiResponse<PlatformAdminService.PageResult<PlatformAdminService.AdminView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(adminService.listAdmins(page, pageSize), traceId, clock);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.account.manage')")
    public ApiResponse<PlatformAdminService.AdminView> create(
            @Valid @RequestBody CreateAdminRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                adminService.createAdmin(
                        new PlatformAdminService.CreateAdminCommand(
                                request.username(),
                                request.displayName(),
                                request.password(),
                                request.roleCodes()
                        ),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    @PatchMapping("/{publicId}/status")
    @PreAuthorize("hasAuthority('platform.account.manage')")
    public ApiResponse<PlatformAdminService.AdminView> changeStatus(
            @PathVariable String publicId,
            @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                adminService.changeAdminStatus(publicId, request.status(), principal, traceId),
                traceId,
                clock
        );
    }

    @PostMapping("/{publicId}/roles")
    @PreAuthorize("hasAuthority('platform.account.manage')")
    public ApiResponse<PlatformAdminService.AdminView> replaceRoles(
            @PathVariable String publicId,
            @Valid @RequestBody ReplaceRolesRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                adminService.replaceAdminRoles(publicId, request.roleCodes(), principal, traceId),
                traceId,
                clock
        );
    }

    public record CreateAdminRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotEmpty List<String> roleCodes
    ) {
    }

    public record ChangeStatusRequest(
            @NotBlank
            @Pattern(regexp = "ACTIVE|DISABLED", message = "必须为 ACTIVE 或 DISABLED")
            String status
    ) {
    }

    public record ReplaceRolesRequest(@NotEmpty List<String> roleCodes) {
    }
}