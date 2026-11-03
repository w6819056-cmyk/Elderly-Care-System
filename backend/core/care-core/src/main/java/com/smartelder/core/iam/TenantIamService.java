package com.smartelder.core.iam;

import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.core.iam.internal.JdbcTenantIamRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantIamService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private final JdbcTenantIamRepository repository;

    public TenantIamService(JdbcTenantIamRepository repository) {
        this.repository = repository;
    }

    public static Set<String> resolvePermissions(
            Collection<? extends Collection<String>> groupPermissions,
            Map<String, String> overrides,
            Set<String> registeredPermissions) {
        Set<String> result = new LinkedHashSet<>();
        for (Collection<String> group : groupPermissions) {
            for (String permission : group) {
                requireRegistered(Set.of(permission), registeredPermissions);
                result.add(permission);
            }
        }
        overrides.forEach((permission, effect) -> {
            requireRegistered(Set.of(permission), registeredPermissions);
            if (!"ALLOW".equals(effect) && !"DENY".equals(effect)) {
                throw new IllegalArgumentException("effect must be ALLOW or DENY");
            }
            if ("ALLOW".equals(effect)) result.add(permission);
            else result.remove(permission);
        });
        return Set.copyOf(result);
    }

    public static void requireRoleDomain(String staffDomain, String roleDomain) {
        if (!staffDomain.equals(roleDomain)) {
            throw new IllegalArgumentException("role domain must match staff domain");
        }
    }

    public static void requireRoleMutable(boolean protectedRole) {
        if (protectedRole) throw new IllegalStateException("protected role cannot be modified");
    }

    @Transactional(readOnly = true)
    public StaffAccessDetails staffAccessDetails(String publicId,TenantAccessContext actor){
        requirePermission(actor,"iam.staff.view");
        StaffView staff=listStaff(actor).stream().filter(s->s.publicId().equals(publicId)).findFirst().orElseThrow(()->new IllegalStateException("staff not found or outside scope"));
        return new StaffAccessDetails(repository.staffRoleCodes(staff.id()),repository.isProtectedOwnerStaff(publicId),"STORE".equals(staff.staffType())?"STORES":repository.staffScopeType(staff.id()),repository.staffStorePublicIds(staff.id()),repository.staffGroupPublicIds(staff.id()));
    }
    public record StaffAccessDetails(Set<String> roleCodes,boolean protectedOwner,String scopeType,Set<String> storePublicIds,Set<String> storeGroupPublicIds){}

    @Transactional(readOnly = true)
    public GroupDetails permissionGroupDetails(String publicId, TenantAccessContext actor) {
        requirePermission(actor,"iam.permission-group.view");
        PermissionGroupView group=repository.findPermissionGroup(publicId);
        if(group==null)throw new IllegalStateException("permission group not found");
        return new GroupDetails(group,repository.groupPermissionCodes(group.id()));
    }
    @Transactional(readOnly = true)
    public RoleDetails roleDetails(String publicId, TenantAccessContext actor) {
        requirePermission(actor,"iam.role.view");
        RoleView role=repository.findRoleByPublicId(publicId);
        if(role==null)throw new IllegalStateException("role not found");
        return new RoleDetails(role,repository.roleGroupCodes(role.id()),repository.roleEffects(role.id()));
    }
    public record GroupDetails(PermissionGroupView group,Set<String> permissionCodes){}
    public record RoleDetails(RoleView role,Set<String> permissionGroupCodes,Map<String,String> permissionEffects){}

    @Transactional(readOnly = true)
    public java.util.List<PermissionDefinitionView> listPermissionDefinitions(
            TenantAccessContext actor) {
        requirePermission(actor, "iam.role.view");
        return repository.listPermissionDefinitions();
    }

    @Transactional(readOnly = true)
    public java.util.List<PermissionGroupView> listPermissionGroups(TenantAccessContext actor) {
        requirePermission(actor, "iam.permission-group.view");
        return repository.listPermissionGroups();
    }

    @Transactional(readOnly = true)
    public java.util.List<RoleView> listRoles(TenantAccessContext actor) {
        requirePermission(actor, "iam.role.view");
        return repository.listRoles();
    }

    @Transactional(readOnly = true)
    public java.util.List<StaffView> listStaff(TenantAccessContext actor) {
        requirePermission(actor, "iam.staff.view");
        if (!"HQ".equals(actor.staffType())) throw new IllegalStateException("only HQ staff may view all staff");
        return repository.listStaff().stream().filter(staff -> {
            if ("HQ".equals(staff.staffType())) {
                return "ALL".equals(actor.dataScope().type()) || staff.id() == actor.staffId();
            }
            return "ALL".equals(actor.dataScope().type())
                    || actor.dataScope().storeIds().contains(staff.homeStoreId());
        }).toList();
    }

    @Transactional
    public void replaceRolePermissions(String publicId, Set<String> groupCodes, Map<String, String> effects,
                                       TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.role.manage");
        RoleView role = repository.findRoleByPublicId(publicId);
        if (role == null) throw new IllegalStateException("role not found");
        requireRoleMutable(role.protectedRole());
        requireRegistered(effects.keySet(), repository.activePermissionCodes());
        validateEffects(effects);
        for (String groupCode : groupCodes) {
            if (repository.findPermissionGroupByCode(groupCode) == null) {
                throw new IllegalArgumentException("unknown permission group: " + groupCode);
            }
        }
        repository.replaceRolePermissions(publicId, groupCodes, effects, actor.staffId(), traceId);
    }

    @Transactional
    public void replaceStaffRoles(String staffPublicId, Set<String> roleCodes,
                                  TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.staff.manage");
        if (!"HQ".equals(actor.staffType())) throw new IllegalStateException("only HQ staff may assign roles");
        StaffView staff = repository.findStaffByPublicId(staffPublicId);
        if (staff == null || !"ACTIVE".equals(staff.status())) throw new IllegalStateException("staff not found or inactive");
        requireStaffInScope(actor, staff);
        for (String roleCode : roleCodes) {
            RoleView role = repository.findRoleByCode(roleCode);
            if (role == null) throw new IllegalArgumentException("unknown role: " + roleCode);
            if (role.protectedRole()) throw new IllegalStateException("protected role cannot be assigned directly");
            requireRoleDomain(staff.staffType(), role.roleDomain());
        }
        if (repository.isProtectedOwnerStaff(staffPublicId)) {
            throw new IllegalStateException("protected owner staff roles cannot be changed");
        }
        repository.replaceStaffRoles(staffPublicId, roleCodes, actor.staffId(), traceId);
    }

    @Transactional
    public PermissionGroupView createPermissionGroup(
            CreatePermissionGroupCommand command, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.permission-group.manage");
        validateCode(command.groupCode(), "groupCode");
        requireText(command.name(), "name");
        requireRegistered(command.permissionCodes(), repository.activePermissionCodes());
        return repository.createPermissionGroup(command, actor.staffId(), traceId);
    }

    @Transactional
    public void replacePermissionGroupPermissions(
            String publicId, Set<String> permissionCodes, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.permission-group.manage");
        requireRegistered(permissionCodes, repository.activePermissionCodes());
        PermissionGroupView group = repository.findPermissionGroup(publicId);
        if (group == null) throw new IllegalStateException("permission group not found");
        if ("TENANT_OWNER_ALL".equals(group.groupCode())) {
            throw new IllegalStateException("protected owner permission group cannot be modified");
        }
        repository.replacePermissionGroupPermissions(group.id(), permissionCodes);
        repository.audit(actor.staffId(), "PERMISSION_GROUP_PERMISSIONS_REPLACED", "PERMISSION_GROUP", publicId, traceId);
    }

    @Transactional
    public RoleView createRole(
            CreateRoleCommand command, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.role.manage");
        validateCode(command.roleCode(), "roleCode");
        requireText(command.name(), "name");
        requireDomain(command.roleDomain());
        requireRegistered(command.permissionEffects().keySet(), repository.activePermissionCodes());
        validateEffects(command.permissionEffects());
        for (String groupCode : command.permissionGroupCodes()) {
            if (repository.findPermissionGroupByCode(groupCode) == null) {
                throw new IllegalArgumentException("unknown permission group: " + groupCode);
            }
        }
        return repository.createRole(command, actor.staffId(), traceId);
    }

    @Transactional
    public StaffView createStaff(
            CreateStaffCommand command, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.staff.manage");
        if (!"HQ".equals(actor.staffType())) throw new IllegalStateException("only HQ staff may manage staff");
        requireText(command.username(), "username");
        requireText(command.passwordHash(), "passwordHash");
        requireText(command.displayName(), "displayName");
        requireDomain(command.staffType());
        Long homeStoreId = null;
        if ("STORE".equals(command.staffType())) {
            if (command.homeStorePublicId() == null) throw new IllegalArgumentException("STORE staff requires homeStore");
            homeStoreId = repository.requireActiveStoreId(command.homeStorePublicId());
            requireStoreInScope(actor, homeStoreId);
        } else if (command.homeStorePublicId() != null) {
            throw new IllegalArgumentException("HQ staff cannot have a home store");
        }
        for (String roleCode : command.roleCodes()) {
            RoleView role = repository.findRoleByCode(roleCode);
            if (role == null) throw new IllegalArgumentException("unknown role: " + roleCode);
            if (role.protectedRole()) throw new IllegalStateException("protected role cannot be assigned directly");
            requireRoleDomain(command.staffType(), role.roleDomain());
        }
        return repository.createStaff(command, homeStoreId, actor.staffId(), traceId);
    }

    @Transactional
    public void disableStaff(String staffPublicId, TenantAccessContext actor, String traceId) {
        requirePermission(actor, "iam.staff.manage");
        if (!"HQ".equals(actor.staffType())) throw new IllegalStateException("only HQ staff may manage staff");
        if (repository.isProtectedOwnerStaff(staffPublicId)) {
            throw new IllegalStateException("protected owner staff cannot be disabled");
        }
        StaffView staff = repository.findStaffByPublicId(staffPublicId);
        if (staff == null) throw new IllegalStateException("staff not found");
        requireStaffInScope(actor, staff);
        repository.disableStaff(staffPublicId, actor.staffId(), traceId);
    }

    private static void requirePermission(TenantAccessContext actor, String permission) {
        if (actor == null || !"HQ".equals(actor.staffType()) || !actor.permissionCodes().contains(permission)) {
            throw new IllegalStateException("missing permission: " + permission);
        }
    }

    private static void requireStoreInScope(TenantAccessContext actor, long storeId) {
        if (!"ALL".equals(actor.dataScope().type()) && !actor.dataScope().storeIds().contains(storeId)) {
            throw new IllegalStateException("DATA_SCOPE_DENIED");
        }
    }

    private static void requireStaffInScope(TenantAccessContext actor, StaffView staff) {
        boolean allowed = "ALL".equals(actor.dataScope().type())
                || ("HQ".equals(staff.staffType()) && staff.id() == actor.staffId())
                || ("STORE".equals(staff.staffType()) && actor.dataScope().storeIds().contains(staff.homeStoreId()));
        if (!allowed) throw new IllegalStateException("DATA_SCOPE_DENIED");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static void validateCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) throw new IllegalArgumentException("invalid " + field);
    }

    private static void requireDomain(String value) {
        if (!"HQ".equals(value) && !"STORE".equals(value)) throw new IllegalArgumentException("domain must be HQ or STORE");
    }

    private static void validateEffects(Map<String, String> effects) {
        effects.forEach((permission, effect) -> {
            if (!"ALLOW".equals(effect) && !"DENY".equals(effect)) {
                throw new IllegalArgumentException("effect must be ALLOW or DENY for " + permission);
            }
        });
    }

    private static void requireRegistered(Collection<String> codes, Set<String> registered) {
        Set<String> unknown = new LinkedHashSet<>(codes);
        unknown.removeAll(registered);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unregistered permission: " + unknown);
    }

    public record CreatePermissionGroupCommand(String groupCode, String name, String description,
                                               Set<String> permissionCodes) {
        public CreatePermissionGroupCommand {
            permissionCodes = Set.copyOf(permissionCodes == null ? Set.of() : permissionCodes);
        }
    }

    public record CreateRoleCommand(String roleCode, String name, String roleDomain,
                                    Set<String> permissionGroupCodes, Map<String, String> permissionEffects) {
        public CreateRoleCommand {
            permissionGroupCodes = Set.copyOf(permissionGroupCodes == null ? Set.of() : permissionGroupCodes);
            permissionEffects = Map.copyOf(permissionEffects == null ? Map.of() : permissionEffects);
        }
    }

    public record CreateStaffCommand(String username, String passwordHash, String displayName, String phone,
                                     String staffType, String homeStorePublicId, Set<String> roleCodes) {
        public CreateStaffCommand {
            roleCodes = Set.copyOf(roleCodes == null ? Set.of() : roleCodes);
        }
    }

    public record PermissionGroupView(long id, String publicId, String groupCode, String name,
                                      String description, String status, long version) {}
    public record PermissionDefinitionView(String code, String moduleCode, String name, String description) {}
    public record RoleView(long id, String publicId, String roleCode, String name, String roleDomain,
                           boolean protectedRole, String status, long version) {}
    public record StaffView(long id, String publicId, String username, String displayName, String staffType,
                            Long homeStoreId, String status, long version) {}
}
