package com.smartelder.platform.core.commerce;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class PlanService {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Set<String> BILLING_MODES = Set.of("PERIOD", "PERPETUAL");

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final PlatformAuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public PlanService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            PlatformAuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public PlanView create(
            CreatePlanCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        ValidatedPlan plan = validate(command);
        Instant now = clock.now();
        String publicId = PublicIds.newId("plan");
        try {
            return transactionTemplate.execute(status -> {
                try {
                    jdbcClient.sql("""
                                    INSERT INTO pf_plan(
                                        public_id, code, name, description, amount, minimum_amount,
                                        currency, sale_status, created_at, updated_at, version
                                    ) VALUES (
                                        :publicId, :code, :name, :description, :amount, :minimumAmount,
                                        'CNY', 'ON_SALE', :createdAt, :updatedAt, 0
                                    )
                                    """)
                            .param("publicId", publicId)
                            .param("code", plan.code())
                            .param("name", plan.name())
                            .param("description", plan.description())
                            .param("amount", plan.amount())
                            .param("minimumAmount", plan.minimumAmount())
                            .param("createdAt", Timestamp.from(now))
                            .param("updatedAt", Timestamp.from(now))
                            .update();
                } catch (DuplicateKeyException exception) {
                    throw new PlatformApiException(
                            ErrorCode.PLAN_CODE_CONFLICT,
                            "套餐编码 " + plan.code() + " 已存在",
                            exception
                    );
                }

                long planId = jdbcClient.sql("SELECT id FROM pf_plan WHERE code = :code")
                        .param("code", plan.code())
                        .query(Long.class)
                        .single();
                int sortOrder = 1;
                for (ValidatedPlanItem item : plan.items()) {
                    jdbcClient.sql("""
                                    INSERT INTO pf_plan_module(
                                        plan_id, module_id, billing_mode, duration_days,
                                        store_quota, sort_order
                                    ) VALUES (
                                        :planId, :moduleId, :billingMode, :durationDays,
                                        :storeQuota, :sortOrder
                                    )
                                    """)
                            .param("planId", planId)
                            .param("moduleId", item.moduleId())
                            .param("billingMode", item.billingMode())
                            .param("durationDays", item.durationDays())
                            .param("storeQuota", item.storeQuota())
                            .param("sortOrder", sortOrder++)
                            .update();
                }

                auditService.record(
                        actor.userId(),
                        actor.publicId(),
                        "PLATFORM_PLAN_CREATED",
                        "PLAN",
                        publicId,
                        "{\"itemCount\":" + plan.items().size() + "}",
                        traceId
                );
                return findByPublicId(publicId);
            });
        } catch (DuplicateKeyException exception) {
            throw new PlatformApiException(
                    ErrorCode.PLAN_CODE_CONFLICT,
                    "套餐编码 " + plan.code() + " 已存在",
                    exception
            );
        }
    }

    public PlanView update(String code,CreatePlanCommand command,PlatformPrincipal actor,String traceId) {
        ValidatedPlan plan=validate(command);
        if(!plan.code().equals(code))throw planInvalid("套餐编码不可修改");
        return transactionTemplate.execute(tx -> {
            long id=jdbcClient.sql("SELECT id FROM pf_plan WHERE code=:code FOR UPDATE").param("code",code).query(Long.class).optional()
                    .orElseThrow(()->new PlatformApiException(ErrorCode.PLAN_NOT_FOUND,"套餐不存在"));
            jdbcClient.sql("UPDATE pf_plan SET name=:name,description=:description,amount=:amount,minimum_amount=:minimum,updated_at=:now,version=version+1 WHERE id=:id")
                    .param("name",plan.name()).param("description",plan.description()).param("amount",plan.amount()).param("minimum",plan.minimumAmount()).param("now",Timestamp.from(clock.now())).param("id",id).update();
            jdbcClient.sql("DELETE FROM pf_plan_module WHERE plan_id=:id").param("id",id).update();
            int sort=1;
            for(ValidatedPlanItem item:plan.items()) {
                jdbcClient.sql("INSERT INTO pf_plan_module(plan_id,module_id,billing_mode,duration_days,store_quota,sort_order) VALUES(:plan,:module,:mode,:days,:quota,:sort)")
                        .param("plan",id).param("module",item.moduleId()).param("mode",item.billingMode()).param("days",item.durationDays()).param("quota",item.storeQuota()).param("sort",sort++).update();
            }
            PlanView updated=findByPlanId(id);
            auditService.record(actor.userId(),actor.publicId(),"PLATFORM_PLAN_UPDATED","PLAN",code,null,traceId);
            return updated;
        });
    }

    public List<PlanView> list(String enterpriseId) {
        String normalizedEnterpriseId = requireText(enterpriseId, "enterpriseId", 64);
        long enterprisePk = jdbcClient.sql(
                        "SELECT id FROM pf_enterprise WHERE public_id = :publicId")
                .param("publicId", normalizedEnterpriseId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "企业不存在"));

        List<Long> planIds = jdbcClient.sql("""
                        SELECT id
                        FROM pf_plan
                        WHERE sale_status = 'ON_SALE'
                        ORDER BY created_at DESC, id DESC
                        """)
                .query(Long.class)
                .list();
        return planIds.stream()
                .map(this::findByPlanId)
                .filter(plan -> !allModulesPermanentlyOwned(enterprisePk, plan.items()))
                .toList();
    }

    public List<PlanView> listCatalog() {
        return jdbcClient.sql("SELECT id FROM pf_plan ORDER BY created_at DESC, id DESC")
                .query(Long.class)
                .list()
                .stream()
                .map(this::findByPlanId)
                .toList();
    }

    public PlanView changeStatus(String code, String statusValue, PlatformPrincipal actor, String traceId) {
        String target = statusValue == null ? "" : statusValue.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ON_SALE", "OFF_SALE").contains(target)) {
            throw planInvalid("套餐状态必须为 ON_SALE 或 OFF_SALE");
        }
        String publicId = jdbcClient.sql("SELECT public_id FROM pf_plan WHERE code = :code")
                .param("code", requireText(code, "code", 40))
                .query(String.class)
                .optional()
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "套餐不存在"));
        int updated = jdbcClient.sql("""
                        UPDATE pf_plan
                        SET sale_status = :status, updated_at = :updatedAt, version = version + 1
                        WHERE public_id = :publicId
                        """)
                .param("status", target)
                .param("updatedAt", Timestamp.from(clock.now()))
                .param("publicId", publicId)
                .update();
        if (updated != 1) throw new PlatformApiException(ErrorCode.CONFLICT, "套餐状态更新冲突");
        auditService.record(actor.userId(), actor.publicId(), "PLATFORM_PLAN_STATUS_CHANGED",
                "PLAN", publicId, "{\"status\":\"" + target + "\"}", traceId);
        return findByPublicId(publicId);
    }

    private PlanView findByPublicId(String publicId) {
        long planId = jdbcClient.sql("SELECT id FROM pf_plan WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query(Long.class)
                .single();
        return findByPlanId(planId);
    }

    private PlanView findByPlanId(long planId) {
        PlanRow plan = jdbcClient.sql("""
                        SELECT public_id, code, name, description, amount, minimum_amount,
                               currency, sale_status, created_at, updated_at
                        FROM pf_plan
                        WHERE id = :planId
                        """)
                .param("planId", planId)
                .query((rs, rowNum) -> mapPlan(rs))
                .single();
        List<PlanItemView> items = jdbcClient.sql("""
                        SELECT m.code AS module_code, m.name AS module_name,
                               pm.billing_mode, pm.duration_days, pm.store_quota,
                               p.amount AS standard_amount
                        FROM pf_plan_module pm
                        JOIN pf_module_definition m ON m.id = pm.module_id
                        LEFT JOIN pf_module_price p
                          ON p.module_id = pm.module_id
                         AND p.billing_mode = pm.billing_mode
                         AND p.duration_days = pm.duration_days
                         AND p.sale_status = 'ON_SALE'
                        WHERE pm.plan_id = :planId
                        ORDER BY pm.sort_order, pm.id
                        """)
                .param("planId", planId)
                .query((rs, rowNum) -> new PlanItemView(
                        rs.getString("module_code"),
                        rs.getString("module_name"),
                        rs.getString("billing_mode"),
                        rs.getInt("duration_days"),
                        rs.getInt("store_quota"),
                        nullableMoney(rs.getBigDecimal("standard_amount"))
                ))
                .list();
        return new PlanView(
                plan.publicId(),
                plan.code(),
                plan.name(),
                plan.description(),
                plan.amount(),
                plan.minimumAmount(),
                plan.currency(),
                plan.saleStatus(),
                items,
                plan.createdAt(),
                plan.updatedAt()
        );
    }

    private boolean allModulesPermanentlyOwned(long enterpriseId, List<PlanItemView> items) {
        if (items.isEmpty()) {
            return false;
        }
        for (PlanItemView item : items) {
            boolean perpetual = jdbcClient.sql("""
                            SELECT COUNT(*)
                            FROM pf_entitlement e
                            JOIN pf_module_definition m ON m.id = e.module_id
                            WHERE e.enterprise_id = :enterpriseId
                              AND m.code = :moduleCode
                              AND e.license_type = 'FORMAL'
                              AND e.status = 'ACTIVE'
                              AND e.perpetual = TRUE
                            """)
                    .param("enterpriseId", enterpriseId)
                    .param("moduleCode", item.moduleCode())
                    .query(Long.class)
                    .single() > 0;
            if (!perpetual) {
                return false;
            }
        }
        return true;
    }

    private ValidatedPlan validate(CreatePlanCommand command) {
        if (command == null) {
            throw planInvalid("套餐请求不能为空");
        }
        String code = requireText(command.code(), "code", 40);
        if (!CODE.matcher(code).matches()) {
            throw planInvalid("套餐编码必须以小写字母开头，且只能包含小写字母、数字和下划线");
        }
        String name = requireText(command.name(), "name", 100);
        String description = command.description() == null ? "" : command.description().trim();
        if (description.length() > 1000) {
            throw planInvalid("description 长度不能超过 1000");
        }
        BigDecimal amount = parseAmount(command.amount(), "amount");
        BigDecimal minimumAmount = parseAmount(command.minimumAmount(), "minimumAmount");
        if (amount.signum() <= 0 || minimumAmount.signum() < 0 || amount.compareTo(minimumAmount) < 0) {
            throw planInvalid("套餐金额必须大于 0，且不能低于最低成交价");
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw planInvalid("套餐至少包含一个模块");
        }
        if (command.items().size() > 100) {
            throw planInvalid("套餐最多包含 100 个模块");
        }

        Set<String> moduleCodes = new HashSet<>();
        List<ValidatedPlanItem> items = new java.util.ArrayList<>();
        for (PlanItemCommand item : command.items()) {
            if (item == null) {
                throw planInvalid("套餐模块不能为空");
            }
            String moduleCode = requireText(item.moduleCode(), "moduleCode", 40);
            if (!moduleCodes.add(moduleCode)) {
                throw planInvalid("套餐不能重复包含模块 " + moduleCode);
            }
            ModulePriceRow module = findModulePrice(moduleCode, item);
            items.add(new ValidatedPlanItem(
                    module.moduleId(),
                    module.moduleCode(),
                    module.billingMode(),
                    module.durationDays(),
                    module.storeQuota()
            ));
        }
        return new ValidatedPlan(
                code,
                name,
                description,
                amount,
                minimumAmount,
                List.copyOf(items)
        );
    }

    private ModulePriceRow findModulePrice(String moduleCode, PlanItemCommand item) {
        String billingMode = item.billingMode() == null
                ? ""
                : item.billingMode().trim().toUpperCase(Locale.ROOT);
        if (!BILLING_MODES.contains(billingMode)) {
            throw planInvalid("billingMode 必须为 PERIOD 或 PERPETUAL");
        }
        int durationDays = item.durationDays();
        if ("PERIOD".equals(billingMode) && durationDays <= 0) {
            throw planInvalid("PERIOD 的 durationDays 必须大于 0");
        }
        if ("PERPETUAL".equals(billingMode) && durationDays != 0) {
            throw planInvalid("PERPETUAL 的 durationDays 必须为 0");
        }
        if (item.storeQuota() <= 0) {
            throw planInvalid("storeQuota 必须大于 0");
        }
        return jdbcClient.sql("""
                        SELECT m.id AS module_id, m.code AS module_code, m.sale_status,
                               p.billing_mode, p.duration_days, p.store_quota
                        FROM pf_module_definition m
                        JOIN pf_module_price p
                          ON p.module_id = m.id
                         AND p.billing_mode = :billingMode
                         AND p.duration_days = :durationDays
                         AND p.sale_status = 'ON_SALE'
                        WHERE m.code = :moduleCode
                        """)
                .param("moduleCode", moduleCode)
                .param("billingMode", billingMode)
                .param("durationDays", durationDays)
                .query((rs, rowNum) -> new ModulePriceRow(
                        rs.getLong("module_id"),
                        rs.getString("module_code"),
                        rs.getString("sale_status"),
                        rs.getString("billing_mode"),
                        rs.getInt("duration_days"),
                        rs.getInt("store_quota")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.MODULE_PRICE_NOT_FOUND,
                        "模块 " + moduleCode + " 缺少匹配的在售价格方案"
                ))
                .validated(item.storeQuota());
    }

    private static PlanRow mapPlan(ResultSet rs) throws SQLException {
        return new PlanRow(
                rs.getString("public_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                money(rs.getBigDecimal("amount")),
                money(rs.getBigDecimal("minimum_amount")),
                rs.getString("currency"),
                rs.getString("sale_status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static BigDecimal parseAmount(String value, String field) {
        String normalized = requireText(value, field, 20);
        try {
            BigDecimal amount = new BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.scale() > 2 || amount.precision() - amount.scale() > 10) {
                throw planInvalid(field + " 格式无效");
            }
            return amount;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new PlatformApiException(
                    ErrorCode.PLAN_INVALID,
                    field + " 必须是最多两位小数的金额",
                    exception
            );
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal nullableMoney(BigDecimal amount) {
        return amount == null ? null : money(amount);
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw planInvalid(field + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static PlatformApiException planInvalid(String message) {
        return new PlatformApiException(ErrorCode.PLAN_INVALID, message);
    }

    private record ValidatedPlan(
            String code,
            String name,
            String description,
            BigDecimal amount,
            BigDecimal minimumAmount,
            List<ValidatedPlanItem> items
    ) {
    }

    private record ValidatedPlanItem(
            long moduleId,
            String moduleCode,
            String billingMode,
            int durationDays,
            int storeQuota
    ) {
    }

    private record ModulePriceRow(
            long moduleId,
            String moduleCode,
            String saleStatus,
            String billingMode,
            int durationDays,
            int storeQuota
    ) {
        ModulePriceRow validated(int requestedStoreQuota) {
            if (!"ON_SALE".equals(saleStatus)) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_NOT_ON_SALE,
                        "模块 " + moduleCode + " 未在售"
                );
            }
            if (requestedStoreQuota != storeQuota) {
                throw planInvalid("套餐门店名额必须与模块价格方案一致");
            }
            return this;
        }
    }

    private record PlanRow(
            String publicId,
            String code,
            String name,
            String description,
            BigDecimal amount,
            BigDecimal minimumAmount,
            String currency,
            String saleStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CreatePlanCommand(
            String code,
            String name,
            String description,
            String amount,
            String minimumAmount,
            List<PlanItemCommand> items
    ) {
    }

    public record PlanItemCommand(
            String moduleCode,
            String billingMode,
            int durationDays,
            int storeQuota
    ) {
    }

    public record PlanItemView(
            String moduleCode,
            String moduleName,
            String billingMode,
            int durationDays,
            int storeQuota,
            BigDecimal standardAmount
    ) {
    }

    public record PlanView(
            String publicId,
            String planCode,
            String name,
            String description,
            BigDecimal amount,
            BigDecimal minimumAmount,
            String currency,
            String saleStatus,
            List<PlanItemView> items,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
