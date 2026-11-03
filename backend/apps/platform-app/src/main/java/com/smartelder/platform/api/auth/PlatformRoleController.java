package com.smartelder.platform.api.auth;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformAdminService;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/roles")
public class PlatformRoleController {

    private final PlatformAdminService adminService;
    private final Clock clock;

    public PlatformRoleController(PlatformAdminService adminService, Clock clock) {
        this.adminService = adminService;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.role.manage')")
    public ApiResponse<List<PlatformAdminService.RoleView>> list(
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(adminService.listRoles(), traceId, clock);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.role.manage')")
    public ApiResponse<PlatformAdminService.RoleView> create(
            @Valid @RequestBody CreateRoleRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                adminService.createRole(
                        new PlatformAdminService.CreateRoleCommand(
                                request.code(),
                                request.name(),
                                request.permissionCodes()
                        ),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    @PutMapping("/{publicId}/permissions")
    @PreAuthorize("hasAuthority('platform.role.manage')")
    public ApiResponse<PlatformAdminService.RoleView> replacePermissions(
            @PathVariable String publicId,
            @Valid @RequestBody ReplacePermissionsRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                adminService.replaceRolePermissions(
                        publicId,
                        request.permissionCodes(),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    public record CreateRoleRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull List<String> permissionCodes
    ) {
    }

    public record ReplacePermissionsRequest(@NotNull List<String> permissionCodes) {
    }
}