package com.smartelder.platform.core.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PlatformTestDatabase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final PlatformPrincipal ACTOR = new PlatformPrincipal(
            1L,
            "adm_test",
            "admin",
            Set.of("SUPER_ADMIN"),
            Set.of("platform.plan.manage", "platform.plan.view")
    );

    @Test
    void createsAndListsPlanWithImmutableModuleConfiguration() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("plan_create")) {
            seedEnterprise(database, "ent_plan_create");
            seedModule(database, "institution", "机构康养", 100, 365, "500.00", 20);
            seedModule(database, "membership", "会员运营", 101, 365, "700.00", 10);
            PlanService service = service(database);

            PlanService.PlanView created = service.create(new PlanService.CreatePlanCommand(
                    "elderly_suite",
                    "养老基础套餐",
                    "机构康养与会员运营组合",
                    "1000.00",
                    "500.00",
                    List.of(
                            new PlanService.PlanItemCommand("institution", "PERIOD", 365, 20),
                            new PlanService.PlanItemCommand("membership", "PERIOD", 365, 10)
                    )
            ), ACTOR, "trace_plan_create");

            assertThat(created.planCode()).isEqualTo("elderly_suite");
            assertThat(created.amount()).isEqualByComparingTo("1000.00");
            assertThat(created.minimumAmount()).isEqualByComparingTo("500.00");
            assertThat(created.items()).extracting(PlanService.PlanItemView::moduleCode)
                    .containsExactly("institution", "membership");
            assertThat(created.items()).extracting(PlanService.PlanItemView::standardAmount)
                    .containsExactly(new BigDecimal("500.00"), new BigDecimal("700.00"));

            assertThat(service.list("ent_plan_create")).singleElement().satisfies(plan -> {
                assertThat(plan.planCode()).isEqualTo("elderly_suite");
                assertThat(plan.items()).hasSize(2);
            });
        }
    }

    @Test
    void hidesPlanOnlyWhenEveryModuleHasPermanentEntitlement() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("plan_hidden")) {
            long enterpriseId = seedEnterprise(database, "ent_plan_hidden");
            long moduleId = seedModule(database, "membership", "会员运营", 100, 365, "500.00", 10);
            PlanService service = service(database);
            service.create(new PlanService.CreatePlanCommand(
                    "membership_suite",
                    "会员套餐",
                    "会员运营",
                    "500.00",
                    "0.00",
                    List.of(new PlanService.PlanItemCommand("membership", "PERIOD", 365, 10))
            ), ACTOR, "trace_plan_hidden");

            seedEntitlement(database, enterpriseId, moduleId, false);
            assertThat(service.list("ent_plan_hidden")).hasSize(1);

            database.jdbcClient().sql("""
                            UPDATE pf_entitlement
                            SET perpetual = TRUE, expires_at = NULL
                            WHERE enterprise_id = :enterpriseId AND module_id = :moduleId
                            """)
                    .param("enterpriseId", enterpriseId)
                    .param("moduleId", moduleId)
                    .update();

            assertThat(service.list("ent_plan_hidden")).isEmpty();
        }
    }

    @Test
    void rejectsInvalidPlanBillingPeriod() {
        try (PlatformTestDatabase database = PlatformTestDatabase.create("plan_invalid")) {
            seedEnterprise(database, "ent_plan_invalid");
            seedModule(database, "institution", "机构康养", 100, 365, "500.00", 20);
            PlanService service = service(database);

            assertThatThrownBy(() -> service.create(new PlanService.CreatePlanCommand(
                    "invalid_suite",
                    "无效套餐",
                    "周期为 0",
                    "500.00",
                    "0.00",
                    List.of(new PlanService.PlanItemCommand("institution", "PERIOD", 0, 20))
            ), ACTOR, "trace_plan_invalid"))
                    .isInstanceOfSatisfying(PlatformApiException.class, exception ->
                            assertThat(exception.code()).isEqualTo(ErrorCode.PLAN_INVALID));
        }
    }

    private static PlanService service(PlatformTestDatabase database) {
        PlatformClock clock = new PlatformClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return new PlanService(
                database.jdbcClient(),
                clock,
                new PlatformAuditService(database.jdbcClient(), clock),
                database.transactionManager()
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
        return database.jdbcClient().sql("SELECT id FROM pf_enterprise WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query(Long.class)
                .single();
    }

    private static long seedModule(
            PlatformTestDatabase database,
            String code,
            String name,
            int menuOrder,
            int durationDays,
            String amount,
            int storeQuota
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
        long moduleId = database.jdbcClient().sql(
                        "SELECT id FROM pf_module_definition WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
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
        return moduleId;
    }

    private static void seedEntitlement(
            PlatformTestDatabase database,
            long enterpriseId,
            long moduleId,
            boolean perpetual
    ) {
        Timestamp now = Timestamp.from(NOW);
        database.jdbcClient().sql("""
                        INSERT INTO pf_entitlement(
                            enterprise_id, module_id, license_type, status, starts_at,
                            expires_at, perpetual, store_quota, source_order_id,
                            version, created_at, updated_at
                        ) VALUES (
                            :enterpriseId, :moduleId, 'FORMAL', 'ACTIVE', :startsAt,
                            :expiresAt, :perpetual, 10, NULL, 0, :createdAt, :updatedAt
                        )
                        """)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .param("startsAt", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
                .param("expiresAt", perpetual ? null : Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")))
                .param("perpetual", perpetual)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }
}