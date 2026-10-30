package com.smartelder.platform.core.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PlatformTestDatabase;
import com.smartelder.platform.licensing.Ed25519LicenseSigner;
import com.smartelder.platform.licensing.Ed25519LicenseVerifier;
import com.smartelder.platform.licensing.LicenseDocument;
import com.smartelder.platform.licensing.LicenseKeyPair;
import com.smartelder.platform.licensing.LicenseSignature;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class EntitlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T06:00:00Z");

    private PlatformTestDatabase database;
    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private LicenseKeyPair keyPair;
    private LicenseIssueService licenseIssueService;
    private EntitlementService service;

    @BeforeEach
    void setUp() {
        database = PlatformTestDatabase.create("entitlement");
        jdbc = database.jdbcClient();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        keyPair = LicenseKeyPair.generate();
        PlatformClock clock = new PlatformClock(Clock.fixed(NOW, ZoneOffset.UTC));
        licenseIssueService = new LicenseIssueService(
                jdbc,
                objectMapper,
                new Ed25519LicenseSigner("license-test-key", keyPair.privateKey()),
                clock,
                database.transactionManager()
        );
        service = new EntitlementService(
                jdbc,
                clock,
                licenseIssueService,
                database.transactionManager()
        );
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void appliesNewPeriodPurchaseAndIssuesSignedLicense() throws Exception {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERIOD", 365, 0, null, null);

        service.applyPaidOrder(fixture.orderId());

        EntitlementRow entitlement = entitlement(fixture.enterpriseId(), fixture.moduleId());
        assertThat(entitlement.licenseType()).isEqualTo("FORMAL");
        assertThat(entitlement.status()).isEqualTo("ACTIVE");
        assertThat(entitlement.startsAt()).isEqualTo(NOW);
        assertThat(entitlement.expiresAt()).isEqualTo(NOW.plusSeconds(365 * 86400L));
        assertThat(entitlement.perpetual()).isFalse();
        assertThat(entitlement.version()).isEqualTo(1);
        assertThat(orderStatus(fixture.orderId())).isEqualTo("FULFILLED");
        assertThat(itemResult(fixture.orderId())).isEqualTo("APPLIED");
        assertThat(instanceLicenseVersion(fixture.instanceId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", fixture.instanceId()))
                .isEqualTo(1);

        LicenseRow license = license(fixture.instanceId());
        assertThat(license.version()).isEqualTo(1);
        LicenseDocument document = objectMapper.readValue(license.payload(), LicenseDocument.class)
                .withSignature(new LicenseSignature("Ed25519", "license-test-key", license.signature()));
        assertThat(new Ed25519LicenseVerifier(keyPair.publicKey()).verify(document)).isTrue();
        assertThat(document.orgNo()).isEqualTo("ORG-ENTITLEMENT");
        assertThat(document.deploymentInstanceId()).isEqualTo("ins_entitlement");
        assertThat(document.modules()).singleElement().satisfies(module -> {
            assertThat(module.code()).isEqualTo("institution");
            assertThat(module.status()).isEqualTo("ACTIVE");
            assertThat(module.expiresAt()).isEqualTo(NOW.plusSeconds(365 * 86400L));
        });
    }

    @Test
    void extendsActivePeriodFromExistingExpiry() {
        Instant originalExpiry = NOW.plusSeconds(86400L);
        Fixture fixture = fixture("ACTIVE", "PAID", "PERIOD", 365, 0, originalExpiry, false);

        service.applyPaidOrder(fixture.orderId());

        assertThat(entitlement(fixture.enterpriseId(), fixture.moduleId()).expiresAt())
                .isEqualTo(originalExpiry.plusSeconds(365 * 86400L));
        assertThat(itemResult(fixture.orderId())).isEqualTo("RENEWED");
    }

    @Test
    void purchaseOfPerpetualLicenseClearsExpiry() {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERPETUAL", 0, 0, NOW.plusSeconds(86400L), false);

        service.applyPaidOrder(fixture.orderId());

        EntitlementRow entitlement = entitlement(fixture.enterpriseId(), fixture.moduleId());
        assertThat(entitlement.perpetual()).isTrue();
        assertThat(entitlement.expiresAt()).isNull();
        assertThat(entitlement.version()).isEqualTo(1);
    }

    @Test
    void applyingSamePaidOrderAgainDoesNotDuplicateEffects() {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERIOD", 365, 0, null, null);

        service.applyPaidOrder(fixture.orderId());
        service.applyPaidOrder(fixture.orderId());

        assertThat(entitlement(fixture.enterpriseId(), fixture.moduleId()).version()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", fixture.instanceId()))
                .isEqualTo(1);
        assertThat(orderStatus(fixture.orderId())).isEqualTo("FULFILLED");
    }

    @Test
    void rejectsUnpaidOrderWithoutChangingEntitlementOrLicense() {
        Fixture fixture = fixture("ACTIVE", "PENDING_PAYMENT", "PERIOD", 365, 0, null, null);

        assertThatThrownBy(() -> service.applyPaidOrder(fixture.orderId()))
                .isInstanceOf(PlatformApiException.class)
                .extracting(exception -> ((PlatformApiException) exception).code())
                .isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);

        assertThat(count("SELECT COUNT(*) FROM pf_entitlement WHERE enterprise_id = :id", fixture.enterpriseId()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", fixture.instanceId()))
                .isZero();
    }

    @Test
    void rejectsFulfillmentWhenRequiredDependencyIsMissingAndRollsBack() {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERIOD", 365, 0, null, null);
        long dependencyModuleId = addModule("base", "基础能力", 20);
        addRequiredDependency(fixture.moduleId(), dependencyModuleId);

        assertThatThrownBy(() -> service.applyPaidOrder(fixture.orderId()))
                .isInstanceOf(PlatformApiException.class)
                .extracting(exception -> ((PlatformApiException) exception).code())
                .isEqualTo(ErrorCode.MODULE_DEPENDENCY_MISSING);

        assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
        assertThat(itemResult(fixture.orderId())).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM pf_entitlement WHERE enterprise_id = :id", fixture.enterpriseId()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", fixture.instanceId()))
                .isZero();
    }

    @Test
    void acceptsActivePlatformGrantAsRequiredDependency() {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERIOD", 365, 0, null, null);
        long dependencyModuleId = addModule("base", "基础能力", 20);
        addRequiredDependency(fixture.moduleId(), dependencyModuleId);
        grantPlatformAccess(fixture.enterpriseId(), dependencyModuleId);

        service.applyPaidOrder(fixture.orderId());

        assertThat(orderStatus(fixture.orderId())).isEqualTo("FULFILLED");
        assertThat(itemResult(fixture.orderId())).isEqualTo("APPLIED");
        assertThat(count("SELECT COUNT(*) FROM pf_entitlement WHERE enterprise_id = :id", fixture.enterpriseId()))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", fixture.instanceId()))
                .isEqualTo(1);
    }

    @Test
    void appliesOrderDependencyBeforeValidatingThePurchasedModule() {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERIOD", 365, 0, null, null);
        long dependencyModuleId = addModule("base", "基础能力", 1);
        addRequiredDependency(fixture.moduleId(), dependencyModuleId);
        addOrderItem(fixture.orderId(), dependencyModuleId, "base", "基础能力", "PENDING");

        service.applyPaidOrder(fixture.orderId());

        assertThat(orderStatus(fixture.orderId())).isEqualTo("FULFILLED");
        assertThat(count("SELECT COUNT(*) FROM pf_entitlement WHERE enterprise_id = :id", fixture.enterpriseId()))
                .isEqualTo(2);
    }

    @Test
    void appliesPendingPackageItemsWhilePreservingSkippedPerpetualItems() {
        Fixture fixture = fixture("ACTIVE", "PAID", "PERPETUAL", 0, 0, null, true);
        jdbc.sql("""
                        UPDATE pf_subscription_order_item
                        SET entitlement_result = 'SKIPPED_EXISTING'
                        WHERE order_id = :orderId
                        """)
                .param("orderId", fixture.orderId())
                .update();
        long pendingModuleId = addModule("community", "社区居家", 20);
        addOrderItem(fixture.orderId(), pendingModuleId, "community", "社区居家", "PENDING");

        service.applyPaidOrder(fixture.orderId());

        assertThat(orderStatus(fixture.orderId())).isEqualTo("FULFILLED");
        assertThat(itemResults(fixture.orderId()))
                .containsExactly("SKIPPED_EXISTING", "APPLIED");
        assertThat(entitlement(fixture.enterpriseId(), fixture.moduleId()).perpetual()).isTrue();
        assertThat(entitlement(fixture.enterpriseId(), pendingModuleId).perpetual()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM pf_license WHERE instance_id = :id", fixture.instanceId()))
                .isEqualTo(1);
    }

    @Test
    void derivesLifecycleStatusAndGivesSuspensionPrecedence() {
        assertThat(EntitlementService.deriveStatus(entitlement("FORMAL", "ACTIVE", NOW.plusSeconds(10), false, false), NOW))
                .isEqualTo("ACTIVE");
        assertThat(EntitlementService.deriveStatus(entitlement("FORMAL", "ACTIVE", NOW.minusSeconds(1), false, false), NOW))
                .isEqualTo("WIND_DOWN");
        assertThat(EntitlementService.deriveStatus(entitlement("FORMAL", "ACTIVE", NOW.minusSeconds(3 * 86400L + 1), false, false), NOW))
                .isEqualTo("READ_ONLY");
        assertThat(EntitlementService.deriveStatus(entitlement("FORMAL", "ACTIVE", null, true, false), NOW))
                .isEqualTo("ACTIVE");
        assertThat(EntitlementService.deriveStatus(entitlement("FORMAL", "SUSPENDED", NOW.plusSeconds(10), false, false), NOW))
                .isEqualTo("SUSPENDED");
    }

    @Test
    void platformGrantSatisfiesDependencyButTrialDoesNot() {
        assertThat(EntitlementService.satisfiesDependency(
                entitlement("PLATFORM_GRANT", "ACTIVE", NOW.plusSeconds(10), false, false),
                NOW
        )).isTrue();
        assertThat(EntitlementService.satisfiesDependency(
                entitlement("TRIAL", "ACTIVE", NOW.plusSeconds(10), false, false),
                NOW
        )).isFalse();
    }

    private Fixture fixture(
            String enterpriseStatus,
            String orderStatus,
            String billingMode,
            int durationDays,
            int storeQuota,
            Instant existingExpiry,
            Boolean existingPerpetual
    ) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.sql("""
                        INSERT INTO pf_enterprise(
                            public_id, org_no, name, contact_name, contact_phone, status,
                            created_at, updated_at, version
                        ) VALUES (
                            'ent_entitlement', 'ORG-ENTITLEMENT', '康颐集团', '张三', '13800000000',
                            :status, :now, :now, 0
                        )
                        """)
                .param("status", enterpriseStatus)
                .param("now", now)
                .update();
        long enterpriseId = jdbc.sql("SELECT id FROM pf_enterprise WHERE public_id = 'ent_entitlement'")
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO pf_module_definition(
                            code, name, description, menu_order, sale_status, trial_enabled,
                            created_at, updated_at, version
                        ) VALUES ('institution', '机构康养', '机构康养模块', 10, 'ON_SALE', FALSE, :now, :now, 0)
                        """)
                .param("now", now)
                .update();
        long moduleId = jdbc.sql("SELECT id FROM pf_module_definition WHERE code = 'institution'")
                .query(Long.class).single();
        if (existingExpiry != null || existingPerpetual != null) {
            jdbc.sql("""
                            INSERT INTO pf_entitlement(
                                enterprise_id, module_id, license_type, status, starts_at,
                                expires_at, perpetual, store_quota, source_order_id,
                                version, created_at, updated_at
                            ) VALUES (
                                :enterpriseId, :moduleId, 'FORMAL', 'ACTIVE', :startsAt,
                                :expiresAt, :perpetual, :storeQuota, NULL, 0, :now, :now
                            )
                            """)
                    .param("enterpriseId", enterpriseId)
                    .param("moduleId", moduleId)
                    .param("startsAt", Timestamp.from(NOW.minusSeconds(86400L)))
                    .param("expiresAt", existingExpiry == null ? null : Timestamp.from(existingExpiry))
                    .param("perpetual", Boolean.TRUE.equals(existingPerpetual))
                    .param("storeQuota", storeQuota)
                    .param("now", now)
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO pf_subscription_order(
                            public_id, order_no, enterprise_id, plan_id, status, currency,
                            original_amount, deduction_amount, payable_amount, request_hash,
                            created_by, paid_at, fulfilled_at, fulfillment_attempts,
                            last_fulfillment_error, created_at, updated_at, version
                        ) VALUES (
                            'order_entitlement', 'PO-ENTITLEMENT', :enterpriseId, NULL, :status, 'CNY',
                            500.00, 0.00, 500.00, 'hash', 1, :paidAt, NULL, 0,
                            NULL, :now, :now, 0
                        )
                        """)
                .param("enterpriseId", enterpriseId)
                .param("status", orderStatus)
                .param("paidAt", "PAID".equals(orderStatus) ? now : null)
                .param("now", now)
                .update();
        long orderId = jdbc.sql("SELECT id FROM pf_subscription_order WHERE public_id = 'order_entitlement'")
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO pf_subscription_order_item(
                            order_id, module_id, module_code, module_name, billing_mode,
                            duration_days, store_quota, standard_amount, deduction_amount,
                            actual_amount, entitlement_result
                        ) VALUES (
                            :orderId, :moduleId, 'institution', '机构康养', :billingMode,
                            :durationDays, :storeQuota, 500.00, 0.00, 500.00, 'PENDING'
                        )
                        """)
                .param("orderId", orderId)
                .param("moduleId", moduleId)
                .param("billingMode", billingMode)
                .param("durationDays", durationDays)
                .param("storeQuota", storeQuota)
                .update();
        jdbc.sql("""
                        INSERT INTO pf_deployment_instance(
                            public_id, enterprise_id, installation_id, tenant_database_id,
                            public_key_pem, fingerprint, status, last_heartbeat_at,
                            license_version, created_at, updated_at, version
                        ) VALUES (
                            'ins_entitlement', :enterpriseId, 'install-1', 'tenant-db-1',
                            :publicKey, 'sha256:fingerprint', 'ACTIVE', NULL,
                            0, :now, :now, 0
                        )
                        """)
                .param("enterpriseId", enterpriseId)
                .param("publicKey", keyPair.publicKeyPem())
                .param("now", now)
                .update();
        long instanceId = jdbc.sql("SELECT id FROM pf_deployment_instance WHERE public_id = 'ins_entitlement'")
                .query(Long.class).single();
        return new Fixture(enterpriseId, moduleId, orderId, instanceId);
    }

    private EntitlementService.Entitlement entitlement(
            String licenseType,
            String status,
            Instant expiresAt,
            boolean perpetual,
            boolean suspended
    ) {
        return new EntitlementService.Entitlement(
                1,
                1,
                1,
                licenseType,
                status,
                NOW.minusSeconds(86400L),
                expiresAt,
                perpetual,
                10,
                suspended
        );
    }

    private long addModule(String code, String name, int menuOrder) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.sql("""
                        INSERT INTO pf_module_definition(
                            code, name, description, menu_order, sale_status, trial_enabled,
                            created_at, updated_at, version
                        ) VALUES (:code, :name, :description, :menuOrder, 'ON_SALE', FALSE, :now, :now, 0)
                        """)
                .param("code", code)
                .param("name", name)
                .param("description", name + "模块")
                .param("menuOrder", menuOrder)
                .param("now", now)
                .update();
        return jdbc.sql("SELECT id FROM pf_module_definition WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private void addRequiredDependency(long moduleId, long dependencyModuleId) {
        jdbc.sql("""
                        INSERT INTO pf_module_dependency(
                            module_id, dependency_module_id, dependency_type, minimum_version
                        ) VALUES (:moduleId, :dependencyModuleId, 'REQUIRED', '1.0.0')
                        """)
                .param("moduleId", moduleId)
                .param("dependencyModuleId", dependencyModuleId)
                .update();
    }

    private void grantPlatformAccess(long enterpriseId, long moduleId) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.sql("""
                        INSERT INTO pf_entitlement(
                            enterprise_id, module_id, license_type, status, starts_at,
                            expires_at, perpetual, store_quota, source_order_id,
                            version, created_at, updated_at
                        ) VALUES (
                            :enterpriseId, :moduleId, 'PLATFORM_GRANT', 'ACTIVE', :startsAt,
                            :expiresAt, FALSE, 20, NULL, 0, :now, :now
                        )
                        """)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .param("startsAt", Timestamp.from(NOW.minusSeconds(86400L)))
                .param("expiresAt", Timestamp.from(NOW.plusSeconds(365 * 86400L)))
                .param("now", now)
                .update();
    }

    private void addOrderItem(
            long orderId,
            long moduleId,
            String moduleCode,
            String moduleName,
            String entitlementResult
    ) {
        jdbc.sql("""
                        INSERT INTO pf_subscription_order_item(
                            order_id, module_id, module_code, module_name, billing_mode,
                            duration_days, store_quota, standard_amount, deduction_amount,
                            actual_amount, entitlement_result
                        ) VALUES (
                            :orderId, :moduleId, :moduleCode, :moduleName, 'PERIOD',
                            365, 20, 300.00, 0.00, 300.00, :entitlementResult
                        )
                        """)
                .param("orderId", orderId)
                .param("moduleId", moduleId)
                .param("moduleCode", moduleCode)
                .param("moduleName", moduleName)
                .param("entitlementResult", entitlementResult)
                .update();
    }

    private EntitlementRow entitlement(long enterpriseId, long moduleId) {
        return jdbc.sql("""
                        SELECT license_type, status, starts_at, expires_at, perpetual, version
                        FROM pf_entitlement
                        WHERE enterprise_id = :enterpriseId AND module_id = :moduleId
                        """)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .query((rs, rowNum) -> new EntitlementRow(
                        rs.getString("license_type"),
                        rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(),
                        instant(rs.getTimestamp("expires_at")),
                        rs.getBoolean("perpetual"),
                        rs.getLong("version")
                ))
                .single();
    }

    private String orderStatus(long orderId) {
        return jdbc.sql("SELECT status FROM pf_subscription_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    private String itemResult(long orderId) {
        return jdbc.sql("SELECT entitlement_result FROM pf_subscription_order_item WHERE order_id = :id")
                .param("id", orderId).query(String.class).single();
    }

    private List<String> itemResults(long orderId) {
        return jdbc.sql("""
                        SELECT entitlement_result
                        FROM pf_subscription_order_item
                        WHERE order_id = :id
                        ORDER BY id
                        """)
                .param("id", orderId)
                .query(String.class)
                .list();
    }

    private long instanceLicenseVersion(long instanceId) {
        return jdbc.sql("SELECT license_version FROM pf_deployment_instance WHERE id = :id")
                .param("id", instanceId).query(Long.class).single();
    }

    private LicenseRow license(long instanceId) {
        return jdbc.sql("""
                        SELECT version, payload_json, signature
                        FROM pf_license WHERE instance_id = :id ORDER BY version DESC LIMIT 1
                        """)
                .param("id", instanceId)
                .query((rs, rowNum) -> new LicenseRow(
                        rs.getLong("version"),
                        rs.getString("payload_json"),
                        rs.getString("signature")
                ))
                .single();
    }

    private int count(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Fixture(long enterpriseId, long moduleId, long orderId, long instanceId) {
    }

    private record EntitlementRow(
            String licenseType,
            String status,
            Instant startsAt,
            Instant expiresAt,
            boolean perpetual,
            long version
    ) {
    }

    private record LicenseRow(long version, String payload, String signature) {
    }
}
