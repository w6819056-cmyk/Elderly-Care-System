package com.smartelder.platform.core.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class PlatformAdminService {

    private static final String SUPER_ADMIN_ROLE_CODE = "PLATFORM_SUPER_ADMIN";
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 72;
    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformAdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuditService auditService;
    private final PlatformClock clock;
    private final ObjectMapper objectMapper;

    public PlatformAdminService(
            PlatformAdminRepository repository,
            PasswordEncoder passwordEncoder,
            PlatformAuditService auditService,
            PlatformClock clock,
            ObjectMapper objectMapper
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<AdminView> listAdmins(int page, int pageSize) {
        validatePage(page, pageSize);
        long total = repository.countAdmins();
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        List<AdminView> items = repository.findAdmins(Math.toIntExact(offset), pageSize)
                .stream()
                .map(this::toAdminView)
                .toList();
        return new PageResult<>(page, pageSize, total, items);
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return repository.findAllRoles().stream()
                .map(this::toRoleView)
                .toList();
    }

    @Transactional
    public AdminView createAdmin(
            CreateAdminCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        requireActor(actor);
        String username = requireText(command.username(), "username", 64);
        String displayName = requireText(command.displayName(), "displayName", 100);
        validatePassword(command.password());
        Set<String> roleCodes = normalizeCodes(command.roleCodes(), "roleCodes", true);
        assertCanAssignRoles(actor, roleCodes);
        if (repository.findByUsername(username).isPresent()) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "平台账号用户名已存在");
        }

        Map<String, Long> roleIds = resolveRoleIds(roleCodes);
        String publicId = PublicIds.newId("adm");
        Instant now = clock.now();
        repository.createAdmin(
                publicId,
                username,
                passwordEncoder.encode(command.password()),
                displayName,
                now
        );
        PlatformAdminRepository.AdminRow created = repository.findAdminByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("created platform admin was not found"));
        repository.replaceAdminRoles(created.id(), new LinkedHashSet<>(roleIds.values()));

        audit(
                actor,
                "PLATFORM_ADMIN_CREATED",
                "PLATFORM_ADMIN",
                created.publicId(),
                Map.of(
                        "username", created.username(),
                        "displayName", created.displayName(),
                        "roleCodes", List.copyOf(roleCodes)
                ),
                traceId
        );
        return toAdminView(created);
    }

    @Transactional
    public RoleView createRole(
            CreateRoleCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        requireActor(actor);
        String code = requireRoleCode(command.code());
        String name = requireText(command.name(), "name", 100);
        Set<String> permissionCodes = normalizeCodes(command.permissionCodes(), "permissionCodes", false);
        if (repository.findRoleByCode(code).isPresent()) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "平台角色编码已存在");
        }

        Set<Long> permissionIds = resolvePermissionIds(permissionCodes);
        String publicId = PublicIds.newId("rol");
        repository.createRole(publicId, code, name, clock.now());
        PlatformAdminRepository.RoleRow created = repository.findRoleByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("created platform role was not found"));
        repository.replaceRolePermissions(created.id(), permissionIds);

        audit(
                actor,
                "PLATFORM_ROLE_CREATED",
                "PLATFORM_ROLE",
                created.publicId(),
                Map.of(
                        "code", created.code(),
                        "name", created.name(),
                        "permissionCodes", List.copyOf(permissionCodes)
                ),
                traceId
        );
        return toRoleView(created);
    }

    @Transactional
    public RoleView replaceRolePermissions(
            String rolePublicId,
            Collection<String> requestedPermissionCodes,
            PlatformPrincipal actor,
            String traceId
    ) {
        requireActor(actor);
        PlatformAdminRepository.RoleRow role = repository.findRoleByPublicId(rolePublicId)
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "平台角色不存在"));
        Set<String> requestedCodes = normalizeCodes(
                requestedPermissionCodes,
                "permissionCodes",
                false
        );
        Set<String> before = repository.findPermissionCodesForRole(role.id());
        if (role.builtIn() && !requestedCodes.containsAll(before)) {
            throw new PlatformApiException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "内置平台超级管理员角色不能通过 API 降权"
            );
        }

        Set<Long> permissionIds = resolvePermissionIds(requestedCodes);
        repository.replaceRolePermissions(role.id(), permissionIds);
        audit(
                actor,
                "PLATFORM_ROLE_PERMISSIONS_REPLACED",
                "PLATFORM_ROLE",
                role.publicId(),
                Map.of(
                        "before", List.copyOf(before),
                        "after", List.copyOf(requestedCodes)
                ),
                traceId
        );
        return toRoleView(role);
    }

    @Transactional
    public AdminView replaceAdminRoles(
            String adminPublicId,
            Collection<String> requestedRoleCodes,
            PlatformPrincipal actor,
            String traceId
    ) {
        requireActor(actor);
        PlatformAdminRepository.AdminRow admin = repository.findAdminByPublicId(adminPublicId)
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "平台账号不存在"));
        Set<String> before = repository.findRoleCodes(admin.id());
        assertCanManageSuperAdmin(actor, before);
        Set<String> requestedCodes = normalizeCodes(requestedRoleCodes, "roleCodes", true);
        assertCanAssignRoles(actor, requestedCodes);

        Map<String, Long> roleIds = resolveRoleIds(requestedCodes);
        repository.replaceAdminRoles(admin.id(), new LinkedHashSet<>(roleIds.values()));
        audit(
                actor,
                "PLATFORM_ADMIN_ROLES_REPLACED",
                "PLATFORM_ADMIN",
                admin.publicId(),
                Map.of(
                        "before", List.copyOf(before),
                        "after", List.copyOf(requestedCodes)
                ),
                traceId
        );
        return toAdminView(admin);
    }

    @Transactional
    public AdminView changeAdminStatus(
            String adminPublicId,
            String requestedStatus,
            PlatformPrincipal actor,
            String traceId
    ) {
        requireActor(actor);
        String status = requireStatus(requestedStatus);
        PlatformAdminRepository.AdminRow admin = repository.findAdminByPublicId(adminPublicId)
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "平台账号不存在"));
        assertCanManageSuperAdmin(actor, repository.findRoleCodes(admin.id()));
        if (status.equals(admin.status())) {
            return toAdminView(admin);
        }

        repository.updateAdminStatus(admin.publicId(), status, clock.now());
        audit(
                actor,
                "PLATFORM_ADMIN_STATUS_CHANGED",
                "PLATFORM_ADMIN",
                admin.publicId(),
                Map.of("before", admin.status(), "after", status),
                traceId
        );
        return toAdminView(
                repository.findAdminByPublicId(admin.publicId())
                        .orElseThrow(() -> new IllegalStateException("updated platform admin was not found"))
        );
    }

    private AdminView toAdminView(PlatformAdminRepository.AdminRow admin) {
        return new AdminView(
                admin.publicId(),
                admin.username(),
                admin.displayName(),
                admin.status(),
                List.copyOf(repository.findRoleCodes(admin.id())),
                admin.createdAt()
        );
    }

    private RoleView toRoleView(PlatformAdminRepository.RoleRow role) {
        return new RoleView(
                role.publicId(),
                role.code(),
                role.name(),
                role.builtIn(),
                List.copyOf(repository.findPermissionCodesForRole(role.id())),
                role.createdAt()
        );
    }

    private Map<String, Long> resolveRoleIds(Set<String> roleCodes) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String roleCode : roleCodes) {
            PlatformAdminRepository.RoleRow role = repository.findRoleByCode(roleCode)
                    .orElseThrow(() -> new PlatformApiException(
                            ErrorCode.INVALID_REQUEST,
                            "未知平台角色: " + roleCode
                    ));
            result.put(roleCode, role.id());
        }
        return result;
    }

    private Set<Long> resolvePermissionIds(Set<String> permissionCodes) {
        Set<Long> result = new LinkedHashSet<>();
        for (String permissionCode : permissionCodes) {
            PlatformAdminRepository.PermissionRow permission = repository
                    .findPermissionByCode(permissionCode)
                    .orElseThrow(() -> new PlatformApiException(
                            ErrorCode.INVALID_REQUEST,
                            "未知平台权限: " + permissionCode
                    ));
            result.add(permission.id());
        }
        return result;
    }

    private void assertCanAssignRoles(PlatformPrincipal actor, Set<String> roleCodes) {
        if (roleCodes.contains(SUPER_ADMIN_ROLE_CODE)
                && !actor.roleCodes().contains(SUPER_ADMIN_ROLE_CODE)) {
            throw new PlatformApiException(ErrorCode.PERMISSION_DENIED, "只有平台超级管理员可以授予超级管理员角色");
        }
    }

    private void assertCanManageSuperAdmin(PlatformPrincipal actor, Set<String> targetRoleCodes) {
        if (targetRoleCodes.contains(SUPER_ADMIN_ROLE_CODE)
                && !actor.roleCodes().contains(SUPER_ADMIN_ROLE_CODE)) {
            throw new PlatformApiException(ErrorCode.PERMISSION_DENIED, "只有平台超级管理员可以管理超级管理员账号");
        }
    }

    private static Set<String> normalizeCodes(
            Collection<String> codes,
            String field,
            boolean required
    ) {
        if (codes == null) {
            throw invalid(field + " 不能为空");
        }
        Set<String> normalized = new TreeSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw invalid(field + " 不能包含空值");
            }
            normalized.add(code.trim());
        }
        if (required && normalized.isEmpty()) {
            throw invalid(field + " 至少需要一项");
        }
        return new LinkedHashSet<>(normalized);
    }

    private static String requireRoleCode(String value) {
        String code = requireText(value, "code", 64);
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw invalid("code 必须为大写字母、数字或下划线组成");
        }
        return code;
    }

    private static String requireStatus(String value) {
        String status = requireText(value, "status", 20);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw invalid("status 必须为 ACTIVE 或 DISABLED");
        }
        return status;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null
                || password.length() < MIN_PASSWORD_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH) {
            throw invalid("password 长度必须为 12-72 个字符");
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw invalid("page 必须大于等于 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw invalid("pageSize 必须为 1-" + MAX_PAGE_SIZE);
        }
    }

    private static void requireActor(PlatformPrincipal actor) {
        if (actor == null) {
            throw new PlatformApiException(ErrorCode.UNAUTHORIZED, "登录状态无效");
        }
    }

    private void audit(
            PlatformPrincipal actor,
            String action,
            String targetType,
            String targetPublicId,
            Object detail,
            String traceId
    ) {
        auditService.record(
                actor.userId(),
                actor.publicId(),
                action,
                targetType,
                targetPublicId,
                toJson(detail),
                requireTraceId(traceId)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize audit detail", exception);
        }
    }

    private static String requireTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        return traceId;
    }

    private static PlatformApiException invalid(String message) {
        return new PlatformApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public record CreateAdminCommand(
            String username,
            String displayName,
            String password,
            Collection<String> roleCodes
    ) {
    }

    public record CreateRoleCommand(
            String code,
            String name,
            Collection<String> permissionCodes
    ) {
    }

    public record AdminView(
            String publicId,
            String username,
            String displayName,
            String status,
            List<String> roleCodes,
            Instant createdAt
    ) {
    }

    public record RoleView(
            String publicId,
            String code,
            String name,
            boolean builtIn,
            List<String> permissionCodes,
            Instant createdAt
    ) {
    }

    public record PageResult<T>(
            int page,
            int pageSize,
            long total,
            List<T> items
    ) {
    }
}