package com.smartelder.platform.core.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PlatformTestDatabase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderQuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void quotesStandalonePurchaseAndRenewsFromCurrentExpiry() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("quote_renewal")) {
            long enterpriseId = seedEnterprise(database, "ent_renewal");
            long moduleId = seedModule(database, "institution", "机构康养", 100);
            seedPeriodPrice(database, moduleId, 365, "500.00", 20);
            OrderQuoteService service = service(database);

            OrderQuoteService.QuoteView first = service.quote(standalone(
                    "ent_renewal",
                    "institution",
                    365,
                    20
            ));

            assertThat(first.originalAmount()).isEqualByComparingTo("500.00");
            assertThat(first.deductionAmount()).isEqualByComparingTo("0.00");
            assertThat(first.payableAmount()).isEqualByComparingTo("500.00");
            assertThat(first.items()).singleElement().satisfies(item -> {
                assertThat(item.currentExpiresAt()).isNull();
                assertThat(item.projectedExpiresAt()).isEqualTo(Instant.parse("2027-10-30T00:00:00Z"));
                assertThat(item.entitlementEffect()).isTrue();
            });

            seedEntitlement(
                    database,
                    enterpriseId,
                    moduleId,
                    "FORMAL",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2027-01-01T00:00:00Z"),
                    false,
                    20
            );

            OrderQuoteService.QuoteView renewal = service.quote(standalone(
                    "ent_renewal",
                    "institution",
                    365,
                    20
            ));

            assertThat(renewal.payableAmount()).isEqualByComparingTo("500.00");
            assertThat(renewal.items()).singleElement().satisfies(item -> {
                assertThat(item.currentExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
                assertThat(item.projectedExpiresAt()).isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
            });
        }
    }

    @Test
    void planDeductsPermanentModulesAndOnlyExtendsUnownedModules() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("quote_plan_deduction")) {
            long enterpriseId = seedEnterprise(database, "ent_plan");
            long moduleA = seedModule(database, "module-a", "模块 A", 100);
            long moduleB = seedModule(database, "module-b", "模块 B", 101);
            seedPeriodPrice(database, moduleA, 365, "500.00", 20);
            seedPeriodPrice(database, moduleB, 365, "500.00", 20);
            seedPlan(
                    database,
                    "elderly-basic-suite",
                    "养老基础套餐",
                    "1000.00",
                    "0.00",
                    List.of(
                            new PlanItem(moduleA, "PERIOD", 365, 20),
                            new PlanItem(moduleB, "PERIOD", 365, 20)
                    )
            );
            seedEntitlement(
                    database,
                    enterpriseId,
                    moduleA,
                    "FORMAL",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null,
                    true,
                    20
            );

            OrderQuoteService.QuoteView quote = service(database).quote(plan("ent_plan", "elderly-basic-suite"));

            assertThat(quote.originalAmount()).isEqualByComparingTo("1000.00");
            assertThat(quote.deductionAmount()).isEqualByComparingTo("500.00");
            assertThat(quote.payableAmount()).isEqualByComparingTo("500.00");
            assertThat(quote.items()).filteredOn(item -> !item.entitlementEffect())
                    .extracting(OrderQuoteService.QuoteItemView::moduleCode)
                    .containsExactly("module-a");
            assertThat(quote.items()).filteredOn(OrderQuoteService.QuoteItemView::entitlementEffect)
                    .extracting(OrderQuoteService.QuoteItemView::moduleCode)
                    .containsExactly("module-b");
        }
    }

    @Test
    void rejectsPlanWhoseModulesAreAllPermanent() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("quote_plan_owned")) {
            long enterpriseId = seedEnterprise(database, "ent_owned");
            long moduleId = seedModule(database, "membership", "会员", 100);
            seedPeriodPrice(database, moduleId, 365, "500.00", 20);
            seedPlan(
                    database,
                    "membership-suite",
                    "会员套餐",
                    "500.00",
                    "0.00",
                    List.of(new PlanItem(moduleId, "PERIOD", 365, 20))
            );
            seedEntitlement(
                    database,
                    enterpriseId,
                    moduleId,
                    "FORMAL",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null,
                    true,
                    20
            );

            assertThatThrownBy(() -> service(database).quote(plan("ent_owned", "membership-suite")))
                    .isInstanceOfSatisfying(PlatformApiException.class, exception ->
                            assertThat(exception.code()).isEqualTo(ErrorCode.PLAN_ALREADY_OWNED));
        }
    }

    @Test
    void requiredDependenciesNeedFormalEntitlementOrSameOrderPurchase() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("quote_dependencies")) {
            long enterpriseId = seedEnterprise(database, "ent_dependencies");
            long baseModule = seedModule(database, "membership", "会员", 100);
            long dependentModule = seedModule(database, "institution", "机构康养", 101);
            seedPeriodPrice(database, baseModule, 365, "300.00", 20);
            seedPeriodPrice(database, dependentModule, 365, "500.00", 20);
            seedDependency(database, dependentModule, baseModule, "REQUIRED");
            OrderQuoteService service = service(database);

            assertThatThrownBy(() -> service.quote(standalone(
                    "ent_dependencies",
                    "institution",
                    365,
                    20
            ))).isInstanceOfSatisfying(PlatformApiException.class, exception ->
                    assertThat(exception.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY_MISSING));

            seedEntitlement(
                    database,
                    enterpriseId,
                    baseModule,
                    "TRIAL",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-11-01T00:00:00Z"),
                    false,
                    20
            );
            assertThatThrownBy(() -> service.quote(standalone(
                    "ent_dependencies",
                    "institution",
                    365,
                    20
            ))).isInstanceOfSatisfying(PlatformApiException.class, exception ->
                    assertThat(exception.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY_MISSING));

            OrderQuoteService.QuoteView sameOrder = service.quote(new OrderQuoteService.QuoteCommand(
                    "ent_dependencies",
                    null,
                    List.of(
                            new OrderQuoteService.QuoteItem("institution", null, null, null),
                            new OrderQuoteService.QuoteItem("membership", null, null, null)
                    ),
                    new OrderQuoteService.BillingPeriod("PERIOD", 365, 20)
            ));
            assertThat(sameOrder.payableAmount()).isEqualByComparingTo("800.00");
        }
    }

    @Test
    void rejectsStandalonePurchaseWhenModuleIsAlreadyPerpetual() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("quote_perpetual")) {
            long enterpriseId = seedEnterprise(database, "ent_perpetual");
            long moduleId = seedModule(database, "membership", "会员", 100);
            seedPeriodPrice(database, moduleId, 365, "500.00", 20);
            seedEntitlement(
                    database,
                    enterpriseId,
                    moduleId,
                    "FORMAL",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    null,
                    true,
                    20
            );

            assertThatThrownBy(() -> service(database).quote(standalone(
                    "ent_perpetual",
                    "membership",
                    365,
                    20
            ))).isInstanceOfSatisfying(PlatformApiException.class, exception ->
                    assertThat(exception.code()).isEqualTo(ErrorCode.MODULE_ALREADY_PERPETUAL));
        }
    }

    private static OrderQuoteService service(PlatformTestDatabase database) {
        return new OrderQuoteService(
                database.jdbcClient(),
                new PlatformClock(Clock.fixed(NOW, ZoneOffset.UTC))
        );
    }

    private static OrderQuoteService.QuoteCommand standalone(
            String enterpriseId,
            String moduleCode,
            int durationDays,
            int storeQuota
    ) {
        return new OrderQuoteService.QuoteCommand(
                enterpriseId,
                null,
                List.of(new OrderQuoteService.QuoteItem(moduleCode, null, null, null)),
                new OrderQuoteService.BillingPeriod("PERIOD", durationDays, storeQuota)
        );
    }

    private static OrderQuoteService.QuoteCommand plan(String enterpriseId, String planCode) {
        return new OrderQuoteService.QuoteCommand(
                enterpriseId,
                planCode,
                List.of(),
                null
        );
    }

    private static long seedEnterprise(PlatformTestDatabase database, String publicId) {
        Timestamp now = Timestamp.from(NOW);
        database.jdbcClient().sql("""
                        INSERT INTO pf_enterprise(
                            public_id, org_no, name, contact_name, contact_phone,
                            status, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :orgNo, '测试企业', '联系人', '13800000000',
                            'ACTIVE', :createdAt, :updatedAt, 0
                        )
                        """)
                .param("publicId", publicId)
                .param("orgNo", publicId.toUpperCase())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return enterpriseId(database, publicId);
    }

    private static long enterpriseId(PlatformTestDatabase database, String publicId) {
        return database.jdbcClient().sql("SELECT id FROM pf_enterprise WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query(Long.class)
                .single();
    }

    private static long seedModule(
            PlatformTestDatabase database,
            String code,
            String name,
            int menuOrder
    ) {
        Timestamp now = Timestamp.from(NOW);
        database.jdbcClient().sql("""
                        INSERT INTO pf_module_definition(
                            code, name, description, menu_order, sale_status,
                            trial_enabled, created_at, updated_at, version
                        ) VALUES (
                            :code, :name, '测试模块', :menuOrder, 'ON_SALE',
                            TRUE, :createdAt, :updatedAt, 0
                        )
                        """)
                .param("code", code)
                .param("name", name)
                .param("menuOrder", menuOrder)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return moduleId(database, code);
    }

    private static long moduleId(PlatformTestDatabase database, String code) {
        return database.jdbcClient().sql("SELECT id FROM pf_module_definition WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private static void seedPeriodPrice(
            PlatformTestDatabase database,
            long moduleId,
            int durationDays,
            String amount,
            int storeQuota
    ) {
        database.jdbcClient().sql("""
                        INSERT INTO pf_module_price(
                            module_id, billing_mode, duration_days, amount,
                            currency, store_quota, trial_days, sale_status
                        ) VALUES (
                            :moduleId, 'PERIOD', :durationDays, :amount,
                            'CNY', :storeQuota, NULL, 'ON_SALE'
                        )
                        """)
                .param("moduleId", moduleId)
                .param("durationDays", durationDays)
                .param("amount", new BigDecimal(amount))
                .param("storeQuota", storeQuota)
                .update();
    }

    private static void seedPlan(
            PlatformTestDatabase database,
            String code,
            String name,
            String amount,
            String minimumAmount,
            List<PlanItem> items
    ) {
        Timestamp now = Timestamp.from(NOW);
        database.jdbcClient().sql("""
                        INSERT INTO pf_plan(
                            public_id, code, name, description, amount, minimum_amount,
                            currency, sale_status, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :code, :name, '测试套餐', :amount, :minimumAmount,
                            'CNY', 'ON_SALE', :createdAt, :updatedAt, 0
                        )
                        """)
                .param("publicId", "plan_" + code)
                .param("code", code)
                .param("name", name)
                .param("amount", new BigDecimal(amount))
                .param("minimumAmount", new BigDecimal(minimumAmount))
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        long planId = database.jdbcClient().sql("SELECT id FROM pf_plan WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
        int sortOrder = 1;
        for (PlanItem item : items) {
            database.jdbcClient().sql("""
                            INSERT INTO pf_plan_module(
                                plan_id, module_id, billing_mode, duration_days, store_quota, sort_order
                            ) VALUES (
                                :planId, :moduleId, :billingMode, :durationDays, :storeQuota, :sortOrder
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
    }

    private static void seedDependency(
            PlatformTestDatabase database,
            long moduleId,
            long dependencyModuleId,
            String type
    ) {
        database.jdbcClient().sql("""
                        INSERT INTO pf_module_dependency(
                            module_id, dependency_module_id, dependency_type, minimum_version
                        ) VALUES (
                            :moduleId, :dependencyModuleId, :type, '1.0.0'
                        )
                        """)
                .param("moduleId", moduleId)
                .param("dependencyModuleId", dependencyModuleId)
                .param("type", type)
                .update();
    }

    private static void seedEntitlement(
            PlatformTestDatabase database,
            long enterpriseId,
            long moduleId,
            String licenseType,
            Instant startsAt,
            Instant expiresAt,
            boolean perpetual,
            int storeQuota
    ) {
        Timestamp now = Timestamp.from(NOW);
        database.jdbcClient().sql("""
                        INSERT INTO pf_entitlement(
                            enterprise_id, module_id, license_type, status, starts_at,
                            expires_at, perpetual, store_quota, source_order_id,
                            version, created_at, updated_at
                        ) VALUES (
                            :enterpriseId, :moduleId, :licenseType, 'ACTIVE', :startsAt,
                            :expiresAt, :perpetual, :storeQuota, NULL,
                            0, :createdAt, :updatedAt
                        )
                        """)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .param("licenseType", licenseType)
                .param("startsAt", Timestamp.from(startsAt))
                .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .param("perpetual", perpetual)
                .param("storeQuota", storeQuota)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    private record PlanItem(long moduleId, String billingMode, int durationDays, int storeQuota) {
    }
}