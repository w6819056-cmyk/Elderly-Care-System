package com.smartelder.platform.core.catalog;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ModuleCatalogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern MODULE_CODE = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern AMOUNT = Pattern.compile("^(0|[1-9]\\d{0,9})(\\.\\d{1,2})?$");
    private static final Map<String, Set<String>> SALE_STATUS_TRANSITIONS = Map.of(
            "DRAFT", Set.of("ON_SALE"),
            "ON_SALE", Set.of("OFF_SALE"),
            "OFF_SALE", Set.of("ON_SALE")
    );

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final PlatformAuditService auditService;
    private final ModuleVersionService versionService;
    private final TransactionTemplate transactionTemplate;

    public ModuleCatalogService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            ModuleVersionService versionService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.versionService = Objects.requireNonNull(versionService, "versionService must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public ModuleView create(
            CreateModuleCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        CreateModuleCommand normalized = validateModule(command);
        List<DependencyCommand> dependencies = validateDependencySyntax(
                normalized.dependencies(),
                normalized.code()
        );
        List<ValidatedPrice> prices = validatePrices(
                normalized.prices(),
                normalized.trialEnabled()
        );
        Instant now = clock.now();
        return transactionTemplate.execute(status -> {
            try {
                jdbcClient.sql("""
                                INSERT INTO pf_module_definition(
                                    code, name, description, menu_order, sale_status,
                                    trial_enabled, created_at, updated_at, version
                                ) VALUES (
                                    :code, :name, :description, :menuOrder, 'DRAFT',
                                    :trialEnabled, :createdAt, :updatedAt, 0
                                )
                                """)
                        .param("code", normalized.code())
                        .param("name", normalized.name())
                        .param("description", normalized.description())
                        .param("menuOrder", normalized.menuOrder())
                        .param("trialEnabled", normalized.trialEnabled())
                        .param("createdAt", Timestamp.from(now))
                        .param("updatedAt", Timestamp.from(now))
                        .update();
            } catch (DuplicateKeyException exception) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_CODE_CONFLICT,
                        "模块编码 " + normalized.code() + " 已存在",
                        exception
                );
            }
            ModuleRow module = findRowByCode(normalized.code()).orElseThrow(() ->
                    new PlatformApiException(ErrorCode.MODULE_NOT_FOUND, "模块不存在"));
            validateDependencyGraph(module, dependencies);
            insertDependencies(module.id(), dependencies);
            insertPrices(module.id(), prices);
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "PLATFORM_MODULE_CREATED",
                    "MODULE",
                    module.code(),
                    "{\"menuOrder\":" + module.menuOrder() + "}",
                    traceId
            );
            return toView(module);
        });
    }
    public PageResult<ModuleView> list(int page, int pageSize, String statusValue) {
        validatePaging(page, pageSize);
        String status = statusValue == null || statusValue.isBlank()
                ? null
                : parseSaleStatus(statusValue);
        String where = status == null ? "" : " WHERE sale_status = :status";
        var countStatement = jdbcClient.sql(
                "SELECT COUNT(*) FROM pf_module_definition" + where);
        if (status != null) {
            countStatement = countStatement.param("status", status);
        }
        long total = countStatement.query(Long.class).single();

        var query = jdbcClient.sql("""
                        SELECT id, code, name, description, menu_order, sale_status,
                               trial_enabled, created_at, updated_at, version
                        FROM pf_module_definition
                        """ + where + " ORDER BY menu_order ASC, code ASC LIMIT :limit OFFSET :offset")
                .param("limit", pageSize)
                .param("offset", (page - 1) * pageSize);
        if (status != null) {
            query = query.param("status", status);
        }
        List<ModuleView> items = query
                .query((rs, rowNum) -> mapRow(rs))
                .list()
                .stream()
                .map(this::toView)
                .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public ModuleView findByCode(String code) {
        String normalizedCode = normalizeModuleCode(code);
        return toView(findRowByCode(normalizedCode).orElseThrow(() ->
                new PlatformApiException(
                        ErrorCode.MODULE_NOT_FOUND,
                        "模块 " + normalizedCode + " 不存在"
                )));
    }

    public ModuleView changeStatus(
            String code,
            String targetValue,
            PlatformPrincipal actor,
            String traceId
    ) {
        String normalizedCode = normalizeModuleCode(code);
        String target = parseSaleStatus(targetValue);
        Instant now = clock.now();
        return transactionTemplate.execute(status -> {
            ModuleRow module = findRowByCodeForUpdate(normalizedCode);
            if (target.equals(module.saleStatus())) {
                return toView(module);
            }
            Set<String> allowed = SALE_STATUS_TRANSITIONS.getOrDefault(
                    module.saleStatus(),
                    Set.of()
            );
            if (!allowed.contains(target)) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_STATE_CONFLICT,
                        "模块状态不能从 " + module.saleStatus() + " 变更为 " + target
                );
            }
            if ("ON_SALE".equals(target)) {
                requireAvailableMenuOrder(module);
            }
            int updated = jdbcClient.sql("""
                            UPDATE pf_module_definition
                            SET sale_status = :target,
                                updated_at = :updatedAt,
                                version = version + 1
                            WHERE id = :id AND version = :version
                            """)
                    .param("target", target)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", module.id())
                    .param("version", module.version())
                    .update();
            if (updated != 1) {
                throw new PlatformApiException(ErrorCode.CONFLICT, "模块状态已被其他操作修改");
            }
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "PLATFORM_MODULE_STATUS_CHANGED",
                    "MODULE",
                    module.code(),
                    "{\"from\":\"" + module.saleStatus() + "\",\"to\":\"" + target + "\"}",
                    traceId
            );
            return toView(findRowByCode(normalizedCode).orElseThrow());
        });
    }

    public List<DependencyView> replaceDependencies(
            String code,
            List<DependencyCommand> dependencies,
            PlatformPrincipal actor,
            String traceId
    ) {
        String normalizedCode = normalizeModuleCode(code);
        List<DependencyCommand> normalizedDependencies = validateDependencySyntax(
                dependencies,
                normalizedCode
        );
        Instant now = clock.now();
        return transactionTemplate.execute(status -> {
            ModuleRow module = findRowByCodeForUpdate(normalizedCode);
            validateDependencyGraph(module, normalizedDependencies);
            bumpModuleVersion(module, now);
            replaceDependencyRows(module.id(), normalizedDependencies);
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "PLATFORM_MODULE_DEPENDENCIES_REPLACED",
                    "MODULE",
                    module.code(),
                    "{\"count\":" + normalizedDependencies.size() + "}",
                    traceId
            );
            return listDependencies(module.id());
        });
    }

    public List<PriceView> replacePrices(
            String code,
            List<PriceCommand> prices,
            PlatformPrincipal actor,
            String traceId
    ) {
        String normalizedCode = normalizeModuleCode(code);
        Instant now = clock.now();
        return transactionTemplate.execute(status -> {
            ModuleRow module = findRowByCodeForUpdate(normalizedCode);
            List<ValidatedPrice> validatedPrices = validatePrices(
                    prices,
                    module.trialEnabled()
            );
            bumpModuleVersion(module, now);
            jdbcClient.sql("DELETE FROM pf_module_price WHERE module_id = :moduleId")
                    .param("moduleId", module.id())
                    .update();
            insertPrices(module.id(), validatedPrices);
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "PLATFORM_MODULE_PRICES_REPLACED",
                    "MODULE",
                    module.code(),
                    "{\"count\":" + validatedPrices.size() + "}",
                    traceId
            );
            return listPrices(module.id());
        });
    }

    private CreateModuleCommand validateModule(CreateModuleCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String code = normalizeModuleCode(command.code());
        String name = command.name() == null ? "" : command.name().trim();
        String description = command.description() == null ? "" : command.description().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "name 不能为空且不能超过 100 个字符");
        }
        if (description.isEmpty() || description.length() > 1000) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "description 不能为空且不能超过 1000 个字符"
            );
        }
        if (command.menuOrder() <= 0) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "menuOrder 必须大于 0");
        }
        return new CreateModuleCommand(
                code,
                name,
                description,
                command.menuOrder(),
                command.trialEnabled(),
                command.dependencies() == null ? List.of() : command.dependencies(),
                command.prices() == null ? List.of() : command.prices()
        );
    }

    private List<DependencyCommand> validateDependencySyntax(
            List<DependencyCommand> dependencies,
            String moduleCode
    ) {
        if (dependencies == null) {
            throw dependencyInvalid("dependencies 不能为空");
        }
        List<DependencyCommand> result = new ArrayList<>();
        Set<String> dependencyCodes = new LinkedHashSet<>();
        for (DependencyCommand dependency : dependencies) {
            if (dependency == null) {
                throw dependencyInvalid("依赖项不能为空");
            }
            String dependencyCode = dependency.moduleCode() == null
                    ? ""
                    : dependency.moduleCode().trim();
            if (!MODULE_CODE.matcher(dependencyCode).matches()) {
                throw dependencyInvalid("依赖模块编码格式无效: " + dependencyCode);
            }
            if (dependencyCode.equals(moduleCode)) {
                throw dependencyInvalid("模块不能依赖自身");
            }
            if (!dependencyCodes.add(dependencyCode)) {
                throw dependencyInvalid("依赖模块 " + dependencyCode + " 重复");
            }
            String type = dependency.type() == null
                    ? ""
                    : dependency.type().trim().toUpperCase(Locale.ROOT);
            if (!"REQUIRED".equals(type) && !"OPTIONAL".equals(type)) {
                throw dependencyInvalid("依赖类型必须为 REQUIRED 或 OPTIONAL");
            }
            String minimumVersion = ModuleVersionService.requireValidVersion(
                    dependency.minimumVersion(),
                    ErrorCode.MODULE_DEPENDENCY_INVALID,
                    "minimumVersion"
            );
            result.add(new DependencyCommand(dependencyCode, type, minimumVersion));
        }
        return List.copyOf(result);
    }

    private List<ValidatedPrice> validatePrices(
            List<PriceCommand> prices,
            boolean trialEnabled
    ) {
        if (prices == null) {
            throw priceInvalid("prices 不能为空");
        }
        List<ValidatedPrice> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (PriceCommand price : prices) {
            if (price == null) {
                throw priceInvalid("价格项不能为空");
            }
            String billingMode = price.billingMode() == null
                    ? ""
                    : price.billingMode().trim().toUpperCase(Locale.ROOT);
            int durationDays = price.durationDays();
            if (!"PERIOD".equals(billingMode) && !"PERPETUAL".equals(billingMode)) {
                throw priceInvalid("billingMode 必须为 PERIOD 或 PERPETUAL");
            }
            if ("PERIOD".equals(billingMode) && durationDays <= 0) {
                throw priceInvalid("PERIOD 价格的 durationDays 必须大于 0");
            }
            if ("PERPETUAL".equals(billingMode) && durationDays != 0) {
                throw priceInvalid("PERPETUAL 价格的 durationDays 必须为 0");
            }
            String key = billingMode + ":" + durationDays;
            if (!keys.add(key)) {
                throw priceInvalid("同一计费模式和周期只能配置一个价格");
            }
            String amountText = price.amount() == null ? "" : price.amount().trim();
            if (!AMOUNT.matcher(amountText).matches()) {
                throw priceInvalid("amount 必须是最多两位小数的非负金额");
            }
            BigDecimal amount = new BigDecimal(amountText).setScale(2);
            String currency = price.currency() == null
                    ? ""
                    : price.currency().trim().toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(currency).matches()) {
                throw priceInvalid("currency 必须是三位大写币种代码");
            }
            if (price.storeQuota() < 1) {
                throw priceInvalid("storeQuota 必须大于 0");
            }
            if (price.trialDays() != null && price.trialDays() <= 0) {
                throw priceInvalid("trialDays 配置后必须大于 0");
            }
            if (price.trialDays() != null && !trialEnabled) {
                throw priceInvalid("模块未启用试用时不能配置 trialDays");
            }
            result.add(new ValidatedPrice(
                    billingMode,
                    durationDays,
                    amount,
                    currency,
                    price.storeQuota(),
                    price.trialDays()
            ));
        }
        return List.copyOf(result);
    }

    private void validateDependencyGraph(
            ModuleRow module,
            List<DependencyCommand> dependencies
    ) {
        List<ModuleRef> modules = jdbcClient.sql("""
                        SELECT id, code
                        FROM pf_module_definition
                        """)
                .query((rs, rowNum) -> new ModuleRef(rs.getLong("id"), rs.getString("code")))
                .list();
        Map<String, Long> idsByCode = new HashMap<>();
        for (ModuleRef candidate : modules) {
            idsByCode.put(candidate.code(), candidate.id());
        }
        Set<Long> dependencyIds = new LinkedHashSet<>();
        for (DependencyCommand dependency : dependencies) {
            Long dependencyId = idsByCode.get(dependency.moduleCode());
            if (dependencyId == null) {
                throw dependencyInvalid("依赖模块 " + dependency.moduleCode() + " 不存在");
            }
            dependencyIds.add(dependencyId);
        }

        Map<Long, List<Long>> graph = new HashMap<>();
        jdbcClient.sql("""
                        SELECT module_id, dependency_module_id
                        FROM pf_module_dependency
                        """)
                .query((rs, rowNum) -> new long[]{
                        rs.getLong("module_id"),
                        rs.getLong("dependency_module_id")
                })
                .list()
                .forEach(edge -> graph.computeIfAbsent(edge[0], ignored -> new ArrayList<>())
                        .add(edge[1]));
        graph.put(module.id(), new ArrayList<>(dependencyIds));
        if (containsCycle(graph)) {
            throw new PlatformApiException(
                    ErrorCode.MODULE_DEPENDENCY_CYCLE,
                    "模块依赖配置会形成循环"
            );
        }
    }

    private static boolean containsCycle(Map<Long, List<Long>> graph) {
        Map<Long, Integer> colors = new HashMap<>();
        for (Long node : graph.keySet()) {
            if (visit(node, graph, colors)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visit(
            Long node,
            Map<Long, List<Long>> graph,
            Map<Long, Integer> colors
    ) {
        int color = colors.getOrDefault(node, 0);
        if (color == 1) {
            return true;
        }
        if (color == 2) {
            return false;
        }
        colors.put(node, 1);
        for (Long dependency : graph.getOrDefault(node, List.of())) {
            if (visit(dependency, graph, colors)) {
                return true;
            }
        }
        colors.put(node, 2);
        return false;
    }

    private void requireAvailableMenuOrder(ModuleRow module) {
        Long conflictingId = jdbcClient.sql("""
                        SELECT id
                        FROM pf_module_definition
                        WHERE sale_status = 'ON_SALE'
                          AND menu_order = :menuOrder
                          AND id <> :id
                        FOR UPDATE
                        """)
                .param("menuOrder", module.menuOrder())
                .param("id", module.id())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (conflictingId != null) {
            throw new PlatformApiException(
                    ErrorCode.MENU_ORDER_CONFLICT,
                    "菜单顺序 " + module.menuOrder() + " 已被其他在售模块占用"
            );
        }
    }

    private void insertDependencies(long moduleId, List<DependencyCommand> dependencies) {
        for (DependencyCommand dependency : dependencies) {
            long dependencyId = jdbcClient.sql("""
                            SELECT id FROM pf_module_definition WHERE code = :code
                            """)
                    .param("code", dependency.moduleCode())
                    .query(Long.class)
                    .single();
            jdbcClient.sql("""
                            INSERT INTO pf_module_dependency(
                                module_id, dependency_module_id, dependency_type, minimum_version
                            ) VALUES (
                                :moduleId, :dependencyId, :dependencyType, :minimumVersion
                            )
                            """)
                    .param("moduleId", moduleId)
                    .param("dependencyId", dependencyId)
                    .param("dependencyType", dependency.type())
                    .param("minimumVersion", dependency.minimumVersion())
                    .update();
        }
    }

    private void replaceDependencyRows(long moduleId, List<DependencyCommand> dependencies) {
        jdbcClient.sql("DELETE FROM pf_module_dependency WHERE module_id = :moduleId")
                .param("moduleId", moduleId)
                .update();
        insertDependencies(moduleId, dependencies);
    }

    private void insertPrices(long moduleId, List<ValidatedPrice> prices) {
        for (ValidatedPrice price : prices) {
            jdbcClient.sql("""
                            INSERT INTO pf_module_price(
                                module_id, billing_mode, duration_days, amount,
                                currency, store_quota, trial_days, sale_status
                            ) VALUES (
                                :moduleId, :billingMode, :durationDays, :amount,
                                :currency, :storeQuota, :trialDays, 'ON_SALE'
                            )
                            """)
                    .param("moduleId", moduleId)
                    .param("billingMode", price.billingMode())
                    .param("durationDays", price.durationDays())
                    .param("amount", price.amount())
                    .param("currency", price.currency())
                    .param("storeQuota", price.storeQuota())
                    .param("trialDays", price.trialDays(), java.sql.Types.INTEGER)
                    .update();
        }
    }

    private ModuleView toView(ModuleRow module) {
        List<ModuleVersionService.VersionView> versions = versionService.listVersions(module.id());
        String highestVersion = versionService.highestVersion(versions).orElse(null);
        return new ModuleView(
                module.code(),
                module.name(),
                module.description(),
                module.menuOrder(),
                module.saleStatus(),
                module.trialEnabled(),
                highestVersion,
                versions,
                listDependencies(module.id()),
                listPrices(module.id()),
                List.of("module." + module.code() + ".access"),
                module.createdAt(),
                module.updatedAt()
        );
    }

    private List<DependencyView> listDependencies(long moduleId) {
        return jdbcClient.sql("""
                        SELECT target.code, dependency.dependency_type, dependency.minimum_version
                        FROM pf_module_dependency dependency
                        JOIN pf_module_definition target
                          ON target.id = dependency.dependency_module_id
                        WHERE dependency.module_id = :moduleId
                        ORDER BY dependency.id ASC
                        """)
                .param("moduleId", moduleId)
                .query((rs, rowNum) -> new DependencyView(
                        rs.getString("code"),
                        rs.getString("dependency_type"),
                        rs.getString("minimum_version")
                ))
                .list();
    }

    private List<PriceView> listPrices(long moduleId) {
        return jdbcClient.sql("""
                        SELECT billing_mode, duration_days, amount, currency,
                               store_quota, trial_days
                        FROM pf_module_price
                        WHERE module_id = :moduleId
                        ORDER BY id ASC
                        """)
                .param("moduleId", moduleId)
                .query((rs, rowNum) -> {
                    Integer trialDays = rs.getObject("trial_days", Integer.class);
                    return new PriceView(
                            rs.getString("billing_mode"),
                            rs.getInt("duration_days"),
                            rs.getBigDecimal("amount").setScale(2).toPlainString(),
                            rs.getString("currency"),
                            rs.getInt("store_quota"),
                            trialDays
                    );
                })
                .list();
    }

    private Optional<ModuleRow> findRowByCode(String code) {
        return jdbcClient.sql("""
                        SELECT id, code, name, description, menu_order, sale_status,
                               trial_enabled, created_at, updated_at, version
                        FROM pf_module_definition
                        WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    private ModuleRow findRowByCodeForUpdate(String code) {
        return jdbcClient.sql("""
                        SELECT id, code, name, description, menu_order, sale_status,
                               trial_enabled, created_at, updated_at, version
                        FROM pf_module_definition
                        WHERE code = :code
                        FOR UPDATE
                        """)
                .param("code", code)
                .query((rs, rowNum) -> mapRow(rs))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.MODULE_NOT_FOUND,
                        "模块 " + code + " 不存在"
                ));
    }

    private void bumpModuleVersion(ModuleRow module, Instant now) {
        int updated = jdbcClient.sql("""
                        UPDATE pf_module_definition
                        SET updated_at = :updatedAt, version = version + 1
                        WHERE id = :id AND version = :version
                        """)
                .param("updatedAt", Timestamp.from(now))
                .param("id", module.id())
                .param("version", module.version())
                .update();
        if (updated != 1) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "模块已被其他操作修改");
        }
    }

    private static ModuleRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModuleRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("menu_order"),
                rs.getString("sale_status"),
                rs.getBoolean("trial_enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version")
        );
    }

    private static String normalizeModuleCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (!MODULE_CODE.matcher(normalized).matches()) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "模块编码必须以小写字母开头，且只能包含小写字母、数字和连字符，长度 2～32"
            );
        }
        return normalized;
    }

    private static String parseSaleStatus(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
        if (!SALE_STATUS_TRANSITIONS.containsKey(normalized)) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "模块状态必须为 DRAFT、ON_SALE 或 OFF_SALE"
            );
        }
        return normalized;
    }

    private static void validatePaging(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "page 必须大于 0，pageSize 必须介于 1 和 " + MAX_PAGE_SIZE + " 之间"
            );
        }
    }

    private static PlatformApiException dependencyInvalid(String message) {
        return new PlatformApiException(ErrorCode.MODULE_DEPENDENCY_INVALID, message);
    }

    private static PlatformApiException priceInvalid(String message) {
        return new PlatformApiException(ErrorCode.MODULE_PRICE_INVALID, message);
    }

    public record CreateModuleCommand(
            String code,
            String name,
            String description,
            int menuOrder,
            boolean trialEnabled,
            List<DependencyCommand> dependencies,
            List<PriceCommand> prices
    ) {
    }

    public record DependencyCommand(
            String moduleCode,
            String type,
            String minimumVersion
    ) {
    }

    public record PriceCommand(
            String billingMode,
            int durationDays,
            String amount,
            String currency,
            int storeQuota,
            Integer trialDays
    ) {
    }

    public record DependencyView(
            String moduleCode,
            String type,
            String minimumVersion
    ) {
    }

    public record PriceView(
            String billingMode,
            int durationDays,
            String amount,
            String currency,
            int storeQuota,
            Integer trialDays
    ) {
    }

    public record ModuleView(
            String moduleCode,
            String name,
            String description,
            int menuOrder,
            String saleStatus,
            boolean trialEnabled,
            String version,
            List<ModuleVersionService.VersionView> versions,
            List<DependencyView> dependencies,
            List<PriceView> prices,
            List<String> permissionCodes,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int pageSize) {
    }

    private record ModuleRow(
            long id,
            String code,
            String name,
            String description,
            int menuOrder,
            String saleStatus,
            boolean trialEnabled,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    private record ModuleRef(long id, String code) {
    }

    private record ValidatedPrice(
            String billingMode,
            int durationDays,
            BigDecimal amount,
            String currency,
            int storeQuota,
            Integer trialDays
    ) {
    }
}
