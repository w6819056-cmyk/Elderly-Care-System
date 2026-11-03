package com.smartelder.platform.core.commerce;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

public class OrderQuoteService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Set<String> BILLING_MODES = Set.of("PERIOD", "PERPETUAL");

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;

    public OrderQuoteService(JdbcClient jdbcClient, PlatformClock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public QuoteView quote(QuoteCommand command) {
        NormalizedQuote normalized = normalize(command);
        EnterpriseRef enterprise = findEnterprise(normalized.enterpriseId());
        if (!"ACTIVE".equals(enterprise.status())) {
            throw new PlatformApiException(ErrorCode.ENTERPRISE_SUSPENDED, "企业当前不可购买模块");
        }
        return normalized.planCode() == null
                ? quoteStandalone(enterprise, normalized)
                : quotePlan(enterprise, normalized.planCode());
    }

    private QuoteView quoteStandalone(EnterpriseRef enterprise, NormalizedQuote quote) {
        if (quote.items().isEmpty() || quote.period() == null) {
            throw invalid("直购模块必须指定非空 items 和 period");
        }
        Instant now = clock.now();
        List<DraftQuoteItem> drafts = new ArrayList<>();
        Set<Long> selectedModuleIds = new HashSet<>();
        String currency = null;
        for (QuoteItem requested : quote.items()) {
            BillingPeriod billing = resolveBilling(requested, quote.period());
            ModuleRow module = findModule(requested.moduleCode());
            if (!"ON_SALE".equals(module.saleStatus())) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_NOT_ON_SALE,
                        "模块 " + module.code() + " 未在售"
                );
            }
            if (!selectedModuleIds.add(module.id())) {
                throw invalid("items 不能包含重复模块");
            }
            ModulePrice price = findPrice(module.id(), billing);
            if (billing.storeQuota() != price.storeQuota()) {
                throw invalid("门店名额与模块价格方案不一致");
            }
            if (currency != null && !currency.equals(price.currency())) {
                throw invalid("同一订单内模块币种必须一致");
            }
            currency = price.currency();
            Optional<EntitlementRow> entitlement = findEntitlement(enterprise.id(), module.id());
            boolean alreadyLicensed = entitlement
                    .filter(row -> row.activeFormal(now))
                    .isPresent();
            if (alreadyLicensed && entitlement.orElseThrow().perpetual()) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_ALREADY_PERPETUAL,
                        "模块 " + module.code() + " 已经永久授权"
                );
            }
            drafts.add(new DraftQuoteItem(module, billing, price, entitlement.orElse(null), alreadyLicensed));
        }
        validateDependencies(enterprise.id(), drafts, now);

        List<QuoteItemView> items = new ArrayList<>();
        BigDecimal payableAmount = ZERO;
        for (DraftQuoteItem draft : drafts) {
            BigDecimal amount = money(draft.price().amount());
            Instant currentExpiresAt = draft.owned()
                    ? draft.entitlement().expiresAt()
                    : null;
            Instant projectedExpiresAt = projectedExpiry(
                    draft.billing().billingMode(),
                    draft.billing().durationDays(),
                    currentExpiresAt,
                    now
            );
            items.add(new QuoteItemView(
                    draft.module().id(),
                    draft.module().code(),
                    draft.module().name(),
                    draft.billing().billingMode(),
                    draft.billing().durationDays(),
                    draft.billing().storeQuota(),
                    draft.price().currency(),
                    amount,
                    ZERO,
                    amount,
                    currentExpiresAt,
                    projectedExpiresAt,
                    true
            ));
            payableAmount = payableAmount.add(amount);
        }
        return new QuoteView(
                enterprise.publicId(),
                null,
                currency == null ? "CNY" : currency,
                payableAmount,
                ZERO,
                payableAmount,
                List.copyOf(items)
        );
    }
    private QuoteView quotePlan(EnterpriseRef enterprise, String planCode) {
        PlanRow plan = findPlan(planCode);
        if (!"ON_SALE".equals(plan.saleStatus())) {
            throw new PlatformApiException(ErrorCode.PLAN_INVALID, "套餐未在售");
        }
        List<PlanModuleRow> planModules = findPlanModules(plan.id());
        if (planModules.isEmpty()) {
            throw new PlatformApiException(ErrorCode.PLAN_INVALID, "套餐未配置任何模块");
        }
        BigDecimal originalAmount = money(plan.amount());
        BigDecimal minimumAmount = money(plan.minimumAmount());
        if (minimumAmount.signum() < 0 || originalAmount.compareTo(minimumAmount) < 0) {
            throw new PlatformApiException(ErrorCode.PLAN_INVALID, "套餐价格不能低于最低成交价");
        }

        Instant now = clock.now();
        List<DraftQuoteItem> drafts = new ArrayList<>();
        Set<Long> selectedModuleIds = new HashSet<>();
        for (PlanModuleRow planModule : planModules) {
            ModuleRow module = findModule(planModule.moduleCode());
            if (!"ON_SALE".equals(module.saleStatus())) {
                throw new PlatformApiException(
                        ErrorCode.MODULE_NOT_ON_SALE,
                        "套餐中的模块 " + module.code() + " 未在售"
                );
            }
            if (!selectedModuleIds.add(module.id())) {
                throw new PlatformApiException(ErrorCode.PLAN_INVALID, "套餐包含重复模块");
            }
            BillingPeriod billing = new BillingPeriod(
                    planModule.billingMode(),
                    planModule.durationDays(),
                    planModule.storeQuota()
            );
            ModulePrice price = findPrice(module.id(), billing);
            if (!plan.currency().equals(price.currency())) {
                throw new PlatformApiException(ErrorCode.PLAN_INVALID, "套餐内模块币种不一致");
            }
            EntitlementRow entitlement = findEntitlement(enterprise.id(), module.id()).orElse(null);
            boolean licensed = entitlement != null && entitlement.activeFormal(now);
            drafts.add(new DraftQuoteItem(module, billing, price, entitlement, licensed));
        }

        if (drafts.stream().allMatch(DraftQuoteItem::owned)) {
            throw new PlatformApiException(
                    ErrorCode.PLAN_ALREADY_OWNED,
                    "套餐内所有模块均已获得正式授权"
            );
        }
        validateDependencies(enterprise.id(), drafts, now);

        BigDecimal ownedStandardAmount = drafts.stream()
                .filter(DraftQuoteItem::owned)
                .map(item -> money(item.price().amount()))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal maximumDeduction = originalAmount.subtract(minimumAmount);
        BigDecimal deductionAmount = ownedStandardAmount.min(maximumDeduction).max(ZERO);
        BigDecimal payableAmount = originalAmount.subtract(deductionAmount);

        List<BigDecimal> ownershipDeductions = allocateByWeight(
                deductionAmount,
                drafts.stream()
                        .filter(DraftQuoteItem::owned)
                        .map(item -> money(item.price().amount()))
                        .toList()
        );
        Map<Long, BigDecimal> deductionByModule = new LinkedHashMap<>();
        int deductionIndex = 0;
        for (DraftQuoteItem draft : drafts) {
            if (draft.owned()) {
                deductionByModule.put(draft.module().id(), ownershipDeductions.get(deductionIndex++));
            }
        }

        List<BigDecimal> effectAmounts = allocateByWeight(
                payableAmount,
                drafts.stream()
                        .filter(draft -> !draft.owned())
                        .map(draft -> money(draft.price().amount()))
                        .toList()
        );
        Map<Long, BigDecimal> amountByModule = new LinkedHashMap<>();
        int effectIndex = 0;
        for (DraftQuoteItem draft : drafts) {
            if (!draft.owned()) {
                amountByModule.put(draft.module().id(), effectAmounts.get(effectIndex++));
            }
        }

        List<QuoteItemView> items = new ArrayList<>();
        for (DraftQuoteItem draft : drafts) {
            BigDecimal standardAmount = money(draft.price().amount());
            BigDecimal itemDeduction = deductionByModule.getOrDefault(draft.module().id(), ZERO);
            BigDecimal actualAmount = amountByModule.getOrDefault(draft.module().id(), ZERO);
            Instant currentExpiresAt = draft.owned() ? draft.entitlement().expiresAt() : null;
            Instant projectedExpiresAt = draft.owned()
                    ? currentExpiresAt
                    : projectedExpiry(
                            draft.billing().billingMode(),
                            draft.billing().durationDays(),
                            null,
                            now
                    );
            items.add(new QuoteItemView(
                    draft.module().id(),
                    draft.module().code(),
                    draft.module().name(),
                    draft.billing().billingMode(),
                    draft.billing().durationDays(),
                    draft.billing().storeQuota(),
                    draft.price().currency(),
                    standardAmount,
                    itemDeduction,
                    actualAmount,
                    currentExpiresAt,
                    projectedExpiresAt,
                    !draft.owned()
            ));
        }
        return new QuoteView(
                enterprise.publicId(),
                plan.code(),
                plan.currency(),
                originalAmount,
                deductionAmount,
                payableAmount,
                List.copyOf(items)
        );
    }

    private void validateDependencies(
            long enterpriseId,
            List<DraftQuoteItem> drafts,
            Instant now
    ) {
        Set<Long> selected = drafts.stream()
                .map(item -> item.module().id())
                .collect(java.util.stream.Collectors.toSet());
        for (DraftQuoteItem draft : drafts) {
            List<DependencyRow> dependencies = jdbcClient.sql("""
                            SELECT d.dependency_module_id, m.code
                            FROM pf_module_dependency d
                            JOIN pf_module_definition m ON m.id = d.dependency_module_id
                            WHERE d.module_id = :moduleId AND d.dependency_type = 'REQUIRED'
                            ORDER BY d.id
                            """)
                    .param("moduleId", draft.module().id())
                    .query((rs, rowNum) -> new DependencyRow(
                            rs.getLong("dependency_module_id"),
                            rs.getString("code")
                    ))
                    .list();
            for (DependencyRow dependency : dependencies) {
                if (selected.contains(dependency.moduleId())) {
                    continue;
                }
                boolean formal = findEntitlement(enterpriseId, dependency.moduleId())
                        .filter(row -> row.activeFormal(now))
                        .isPresent();
                if (!formal) {
                    throw new PlatformApiException(
                            ErrorCode.MODULE_DEPENDENCY_MISSING,
                            "模块 " + draft.module().code()
                                    + " 缺少正式授权的依赖模块 " + dependency.moduleCode()
                    );
                }
            }
        }
    }

    private NormalizedQuote normalize(QuoteCommand command) {
        if (command == null) {
            throw invalid("报价请求不能为空");
        }
        String enterpriseId = requireText(command.enterpriseId(), "enterpriseId", 64);
        String planCode = trimToNull(command.planCode());
        List<QuoteItem> items = command.items() == null ? List.of() : command.items();
        if ((planCode == null) == items.isEmpty()) {
            throw invalid("planCode 与非空 items 必须二选一");
        }
        if (planCode != null) {
            if (command.period() != null) {
                throw invalid("套餐报价不能同时指定 period");
            }
            return new NormalizedQuote(enterpriseId, requireText(planCode, "planCode", 40), List.of(), null);
        }
        if (command.period() == null) {
            throw invalid("直购模块必须指定 period");
        }
        validateBilling(command.period());
        List<QuoteItem> normalizedItems = items.stream()
                .map(item -> {
                    if (item == null) {
                        throw invalid("items 不能包含空项");
                    }
                    return new QuoteItem(
                            requireText(item.moduleCode(), "moduleCode", 40),
                            trimToNull(item.billingMode()),
                            item.durationDays(),
                            item.storeQuota()
                    );
                })
                .toList();
        return new NormalizedQuote(enterpriseId, null, normalizedItems, command.period());
    }

    private BillingPeriod resolveBilling(QuoteItem item, BillingPeriod defaultPeriod) {
        String billingMode = item.billingMode() == null
                ? defaultPeriod.billingMode()
                : item.billingMode().toUpperCase(Locale.ROOT);
        Integer durationDays = item.durationDays() == null
                ? defaultPeriod.durationDays()
                : item.durationDays();
        Integer storeQuota = item.storeQuota() == null
                ? defaultPeriod.storeQuota()
                : item.storeQuota();
        BillingPeriod resolved = new BillingPeriod(billingMode, durationDays, storeQuota);
        validateBilling(resolved);
        return resolved;
    }

    private static void validateBilling(BillingPeriod billing) {
        if (billing == null) {
            throw invalid("价格周期不能为空");
        }
        String mode = billing.billingMode() == null
                ? ""
                : billing.billingMode().trim().toUpperCase(Locale.ROOT);
        if (!BILLING_MODES.contains(mode)) {
            throw invalid("billingMode 必须是 PERIOD 或 PERPETUAL");
        }
        if (billing.durationDays() == null || billing.durationDays() < 0) {
            throw invalid("durationDays 不能小于 0");
        }
        if ("PERIOD".equals(mode) && billing.durationDays() <= 0) {
            throw invalid("周期授权的 durationDays 必须大于 0");
        }
        if (billing.storeQuota() == null || billing.storeQuota() <= 0) {
            throw invalid("storeQuota 必须大于 0");
        }
    }

    private EnterpriseRef findEnterprise(String publicId) {
        return jdbcClient.sql("""
                        SELECT id, public_id, status
                        FROM pf_enterprise
                        WHERE public_id = :publicId
                        """)
                .param("publicId", publicId)
                .query((rs, rowNum) -> new EnterpriseRef(
                        rs.getLong("id"),
                        rs.getString("public_id"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "企业不存在"));
    }

    private ModuleRow findModule(String moduleCode) {
        String code = requireText(moduleCode, "moduleCode", 40);
        return jdbcClient.sql("""
                        SELECT id, code, name, sale_status
                        FROM pf_module_definition
                        WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, rowNum) -> new ModuleRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("sale_status")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.MODULE_NOT_FOUND,
                        "模块 " + code + " 不存在"
                ));
    }

    private PlanRow findPlan(String planCode) {
        String code = requireText(planCode, "planCode", 40);
        return jdbcClient.sql("""
                        SELECT id, public_id, code, amount, minimum_amount, currency, sale_status
                        FROM pf_plan
                        WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, rowNum) -> new PlanRow(
                        rs.getLong("id"),
                        rs.getString("public_id"),
                        rs.getString("code"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("minimum_amount"),
                        rs.getString("currency"),
                        rs.getString("sale_status")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.PLAN_NOT_FOUND,
                        "套餐 " + code + " 不存在"
                ));
    }

    private List<PlanModuleRow> findPlanModules(long planId) {
        return jdbcClient.sql("""
                        SELECT pm.module_id, m.code AS module_code, pm.billing_mode,
                               pm.duration_days, pm.store_quota
                        FROM pf_plan_module pm
                        JOIN pf_module_definition m ON m.id = pm.module_id
                        WHERE pm.plan_id = :planId
                        ORDER BY pm.sort_order, pm.id
                        """)
                .param("planId", planId)
                .query((rs, rowNum) -> new PlanModuleRow(
                        rs.getLong("module_id"),
                        rs.getString("module_code"),
                        rs.getString("billing_mode"),
                        rs.getInt("duration_days"),
                        rs.getInt("store_quota")
                ))
                .list();
    }

    private ModulePrice findPrice(long moduleId, BillingPeriod billing) {
        String mode = billing.billingMode().trim().toUpperCase(Locale.ROOT);
        return jdbcClient.sql("""
                        SELECT amount, currency, store_quota
                        FROM pf_module_price
                        WHERE module_id = :moduleId
                          AND billing_mode = :billingMode
                          AND duration_days = :durationDays
                          AND sale_status = 'ON_SALE'
                        """)
                .param("moduleId", moduleId)
                .param("billingMode", mode)
                .param("durationDays", billing.durationDays())
                .query((rs, rowNum) -> new ModulePrice(
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getInt("store_quota")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.MODULE_PRICE_NOT_FOUND,
                        "未找到匹配的模块价格方案"
                ));
    }

    private Optional<EntitlementRow> findEntitlement(long enterpriseId, long moduleId) {
        return jdbcClient.sql("""
                        SELECT license_type, status, starts_at, expires_at, perpetual, store_quota
                        FROM pf_entitlement
                        WHERE enterprise_id = :enterpriseId AND module_id = :moduleId
                        """)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .query((rs, rowNum) -> mapEntitlement(rs))
                .optional();
    }

    private static EntitlementRow mapEntitlement(ResultSet rs) throws SQLException {
        Timestamp startsAt = rs.getTimestamp("starts_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new EntitlementRow(
                rs.getString("license_type"),
                rs.getString("status"),
                startsAt == null ? null : startsAt.toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getBoolean("perpetual"),
                rs.getInt("store_quota")
        );
    }

    private static Instant projectedExpiry(
            String billingMode,
            int durationDays,
            Instant currentExpiresAt,
            Instant now
    ) {
        if ("PERPETUAL".equalsIgnoreCase(billingMode)) {
            return null;
        }
        Instant start = currentExpiresAt != null && currentExpiresAt.isAfter(now)
                ? currentExpiresAt
                : now;
        return start.plus(durationDays, ChronoUnit.DAYS);
    }

    private static List<BigDecimal> allocateByWeight(BigDecimal total, List<BigDecimal> weights) {
        if (weights.isEmpty()) {
            return List.of();
        }
        BigDecimal normalizedTotal = money(total);
        BigInteger totalCents = normalizedTotal.movePointRight(2).toBigIntegerExact();
        List<BigInteger> normalizedWeights = weights.stream()
                .map(OrderQuoteService::toCents)
                .toList();
        BigInteger weightTotal = normalizedWeights.stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (weightTotal.signum() == 0) {
            normalizedWeights = java.util.Collections.nCopies(weights.size(), BigInteger.ONE);
            weightTotal = BigInteger.valueOf(weights.size());
        }

        List<BigInteger> allocations = new ArrayList<>();
        List<Remainder> remainders = new ArrayList<>();
        BigInteger allocated = BigInteger.ZERO;
        for (int index = 0; index < normalizedWeights.size(); index++) {
            BigInteger[] division = totalCents
                    .multiply(normalizedWeights.get(index))
                    .divideAndRemainder(weightTotal);
            allocations.add(division[0]);
            allocated = allocated.add(division[0]);
            remainders.add(new Remainder(index, division[1]));
        }
        BigInteger leftover = totalCents.subtract(allocated);
        remainders.sort(Comparator
                .comparing(Remainder::remainder)
                .reversed()
                .thenComparingInt(Remainder::index));
        for (int index = 0; index < leftover.intValueExact(); index++) {
            int target = remainders.get(index).index();
            allocations.set(target, allocations.get(target).add(BigInteger.ONE));
        }
        return allocations.stream()
                .map(OrderQuoteService::fromCents)
                .toList();
    }

    private static BigInteger toCents(BigDecimal amount) {
        return money(amount).movePointRight(2).toBigIntegerExact();
    }

    private static BigDecimal fromCents(BigInteger cents) {
        return new BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal money(BigDecimal amount) {
        if (amount == null) {
            throw invalid("金额不能为空");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "金额最多允许两位小数", exception);
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maxLength) {
            throw invalid(field + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static PlatformApiException invalid(String message) {
        return new PlatformApiException(ErrorCode.INVALID_REQUEST, message);
    }

    private record NormalizedQuote(
            String enterpriseId,
            String planCode,
            List<QuoteItem> items,
            BillingPeriod period
    ) {
    }

    private record EnterpriseRef(long id, String publicId, String status) {
    }

    private record ModuleRow(long id, String code, String name, String saleStatus) {
    }

    private record ModulePrice(BigDecimal amount, String currency, int storeQuota) {
    }

    private record PlanRow(
            long id,
            String publicId,
            String code,
            BigDecimal amount,
            BigDecimal minimumAmount,
            String currency,
            String saleStatus
    ) {
    }

    private record PlanModuleRow(
            long moduleId,
            String moduleCode,
            String billingMode,
            int durationDays,
            int storeQuota
    ) {
    }

    private record DependencyRow(long moduleId, String moduleCode) {
    }

    private record DraftQuoteItem(
            ModuleRow module,
            BillingPeriod billing,
            ModulePrice price,
            EntitlementRow entitlement,
            boolean owned
    ) {
    }

    private record EntitlementRow(
            String licenseType,
            String status,
            Instant startsAt,
            Instant expiresAt,
            boolean perpetual,
            int storeQuota
    ) {
        boolean activeFormal(Instant now) {
            boolean active = "ACTIVE".equals(status)
                    && "FORMAL".equals(licenseType)
                    && startsAt != null
                    && !startsAt.isAfter(now)
                    && (perpetual || (expiresAt != null && expiresAt.isAfter(now)));
            return active;
        }
    }

    private record Remainder(int index, BigInteger remainder) {
    }

    public record QuoteCommand(
            String enterpriseId,
            String planCode,
            List<QuoteItem> items,
            BillingPeriod period
    ) {
    }

    public record QuoteItem(
            String moduleCode,
            String billingMode,
            Integer durationDays,
            Integer storeQuota
    ) {
    }

    public record BillingPeriod(String billingMode, Integer durationDays, Integer storeQuota) {
    }

    public record QuoteView(
            String enterpriseId,
            String planCode,
            String currency,
            BigDecimal originalAmount,
            BigDecimal deductionAmount,
            BigDecimal payableAmount,
            List<QuoteItemView> items
    ) {
    }

    public record QuoteItemView(
            long moduleId,
            String moduleCode,
            String moduleName,
            String billingMode,
            int durationDays,
            int storeQuota,
            String currency,
            BigDecimal standardAmount,
            BigDecimal deductionAmount,
            BigDecimal actualAmount,
            Instant currentExpiresAt,
            Instant projectedExpiresAt,
            boolean entitlementEffect
    ) {
    }
}