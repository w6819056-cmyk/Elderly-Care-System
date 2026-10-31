package com.smartelder.api.tenant.hq;

import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.core.iam.TenantIamService;
import com.smartelder.core.iam.TenantDataScopeService;
import com.smartelder.core.iam.TenantIamService.PermissionDefinitionView;
import com.smartelder.core.iam.TenantIamService.PermissionGroupView;
import com.smartelder.core.iam.TenantIamService.RoleView;
import com.smartelder.core.iam.TenantIamService.StaffView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant/v1/hq")
public class HqIamController {

    private final TenantIamService service;
    private final TenantDataScopeService dataScopeService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock = Clock.systemUTC();

    public HqIamController(TenantIamService service, TenantDataScopeService dataScopeService,
                           PasswordEncoder passwordEncoder) {
        this.service = service;
        this.dataScopeService = dataScopeService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/permission-definitions")
    public ApiResponse<List<PermissionDefinitionView>> permissionDefinitions(
            Authentication auth, HttpServletRequest request) {
        return ok(service.listPermissionDefinitions(context(auth)), request);
    }

    @GetMapping("/permission-groups")
    public ApiResponse<List<PermissionGroupView>> permissionGroups(
            Authentication auth, HttpServletRequest request) {
        return ok(service.listPermissionGroups(context(auth)), request);
    }

    @PostMapping("/permission-groups")
    public ApiResponse<PermissionGroupView> createPermissionGroup(
            @Valid @RequestBody CreatePermissionGroupRequest body, Authentication auth, HttpServletRequest request) {
        var command = new TenantIamService.CreatePermissionGroupCommand(
                body.groupCode(), body.name(), body.description(), body.permissionCodes());
        return ok(service.createPermissionGroup(command, context(auth), traceId(request)), request);
    }

    @PutMapping("/permission-groups/{publicId}/permissions")
    public ApiResponse<Void> replaceGroupPermissions(@PathVariable String publicId,
            @RequestBody ReplaceGroupPermissionsRequest body, Authentication auth, HttpServletRequest request) {
        service.replacePermissionGroupPermissions(publicId, body.permissionCodes(), context(auth), traceId(request));
        return ok(null, request);
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> roles(Authentication auth, HttpServletRequest request) {
        return ok(service.listRoles(context(auth)), request);
    }

    @PostMapping("/roles")
    public ApiResponse<RoleView> createRole(
            @Valid @RequestBody CreateRoleRequest body, Authentication auth, HttpServletRequest request) {
        var command = new TenantIamService.CreateRoleCommand(
                body.roleCode(), body.name(), body.roleDomain(), body.permissionGroupCodes(), body.permissionEffects());
        return ok(service.createRole(command, context(auth), traceId(request)), request);
    }

    @PutMapping("/roles/{publicId}/permissions")
    public ApiResponse<Void> replaceRolePermissions(@PathVariable String publicId,
            @RequestBody ReplaceRolePermissionsRequest body, Authentication auth, HttpServletRequest request) {
        service.replaceRolePermissions(publicId, body.permissionGroupCodes(), body.permissionEffects(),
                context(auth), traceId(request));
        return ok(null, request);
    }

    @GetMapping("/staff")
    public ApiResponse<List<StaffView>> staff(Authentication auth, HttpServletRequest request) {
        return ok(service.listStaff(context(auth)), request);
    }

    @PostMapping("/staff")
    public ApiResponse<StaffView> createStaff(
            @Valid @RequestBody CreateStaffRequest body, Authentication auth, HttpServletRequest request) {
        var command = new TenantIamService.CreateStaffCommand(body.username(), passwordEncoder.encode(body.password()),
                body.displayName(), body.phone(), body.staffType(), body.homeStorePublicId(), body.roleCodes());
        return ok(service.createStaff(command, context(auth), traceId(request)), request);
    }

    @PatchMapping("/staff/{publicId}/roles")
    public ApiResponse<Void> replaceStaffRoles(@PathVariable String publicId,
            @RequestBody ReplaceStaffRolesRequest body, Authentication auth, HttpServletRequest request) {
        service.replaceStaffRoles(publicId, body.roleCodes(), context(auth), traceId(request));
        return ok(null, request);
    }

    @PatchMapping("/staff/{publicId}/disable")
    public ApiResponse<Void> disableStaff(@PathVariable String publicId, Authentication auth, HttpServletRequest request) {
        service.disableStaff(publicId, context(auth), traceId(request));
        return ok(null, request);
    }

    @PostMapping("/store-groups")
    public ApiResponse<TenantDataScopeService.StoreGroupView> createStoreGroup(
            @Valid @RequestBody CreateStoreGroupRequest body, Authentication auth, HttpServletRequest request) {
        return ok(dataScopeService.createStoreGroup(
                new TenantDataScopeService.CreateStoreGroupCommand(body.groupCode(), body.name()),
                context(auth), traceId(request)), request);
    }

    @PutMapping("/store-groups/{publicId}/stores")
    public ApiResponse<Void> replaceStoreGroupMembers(@PathVariable String publicId,
            @RequestBody ReplaceStoreGroupMembersRequest body, Authentication auth, HttpServletRequest request) {
        dataScopeService.replaceStoreGroupMembers(publicId, body.storePublicIds(), context(auth), traceId(request));
        return ok(null, request);
    }

    @PutMapping("/staff/{publicId}/data-scope")
    public ApiResponse<Void> replaceStaffScope(@PathVariable String publicId,
            @RequestBody ReplaceStaffScopeRequest body, Authentication auth, HttpServletRequest request) {
        dataScopeService.replaceStaffScope(new TenantDataScopeService.ReplaceStaffScopeCommand(
                publicId, body.scopeType(), body.storePublicIds(), body.storeGroupPublicIds()),
                context(auth), traceId(request));
        return ok(null, request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.success(data, traceId(request), clock);
    }

    private static com.smartelder.core.api.access.TenantAccessContext context(Authentication auth) {
        return ((TenantStaffPrincipal) auth.getPrincipal()).accessContext();
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return value == null ? "tr_unknown" : value.toString();
    }

    public record CreatePermissionGroupRequest(@NotBlank String groupCode, @NotBlank String name,
            String description, Set<String> permissionCodes) {}
    public record ReplaceGroupPermissionsRequest(Set<String> permissionCodes) {
        public ReplaceGroupPermissionsRequest { permissionCodes = Set.copyOf(permissionCodes == null ? Set.of() : permissionCodes); }
    }
    public record CreateRoleRequest(@NotBlank String roleCode, @NotBlank String name, @NotBlank String roleDomain,
            Set<String> permissionGroupCodes, Map<String, String> permissionEffects) {}
    public record ReplaceRolePermissionsRequest(Set<String> permissionGroupCodes, Map<String, String> permissionEffects) {
        public ReplaceRolePermissionsRequest {
            permissionGroupCodes = Set.copyOf(permissionGroupCodes == null ? Set.of() : permissionGroupCodes);
            permissionEffects = Map.copyOf(permissionEffects == null ? Map.of() : permissionEffects);
        }
    }
    public record CreateStaffRequest(@NotBlank String username, @NotBlank @Size(min = 12) String password,
            @NotBlank String displayName, String phone, @NotBlank String staffType,
            String homeStorePublicId, Set<String> roleCodes) {}
    public record ReplaceStaffRolesRequest(Set<String> roleCodes) {
        public ReplaceStaffRolesRequest { roleCodes = Set.copyOf(roleCodes == null ? Set.of() : roleCodes); }
    }
    public record CreateStoreGroupRequest(@NotBlank String groupCode, @NotBlank String name) {}
    public record ReplaceStoreGroupMembersRequest(Set<String> storePublicIds) {
        public ReplaceStoreGroupMembersRequest { storePublicIds = Set.copyOf(storePublicIds == null ? Set.of() : storePublicIds); }
    }
    public record ReplaceStaffScopeRequest(@NotBlank String scopeType, Set<String> storePublicIds,
                                           Set<String> storeGroupPublicIds) {
        public ReplaceStaffScopeRequest {
            storePublicIds = Set.copyOf(storePublicIds == null ? Set.of() : storePublicIds);
            storeGroupPublicIds = Set.copyOf(storeGroupPublicIds == null ? Set.of() : storeGroupPublicIds);
        }
    }
}
