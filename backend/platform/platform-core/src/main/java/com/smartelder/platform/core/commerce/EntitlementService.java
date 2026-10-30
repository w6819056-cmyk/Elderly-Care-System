package com.smartelder.platform.core.commerce;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformClock;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class EntitlementService {

    private static final int WIND_DOWN_DAYS = 3;

    private final JdbcClient jdbcClient;
    private final PlatformClock clock;
    private final LicenseIssueService licenseIssueService;
    private final TransactionTemplate transactionTemplate;

    public EntitlementService(
            JdbcClient jdbcClient,
            PlatformClock clock,
            LicenseIssueService licenseIssueService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.licenseIssueService = Objects.requireNonNull(
                licenseIssueService,
                "licenseIssueService must not be null"
        );
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public void applyPaidOrder(long orderId) {
        transactionTemplate.executeWithoutResult(status -> applyInTransaction(orderId));
    }

    public static String deriveStatus(Entitlement entitlement, Instant now) {
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (entitlement.suspended() || "SUSPENDED".equalsIgnoreCase(entitlement.status())) {
            return "SUSPENDED";
        }
        if (entitlement.perpetual()) {
            return "ACTIVE";
        }
        if (entitlement.startsAt() != null && now.isBefore(entitlement.startsAt())) {
            return "READ_ONLY";
        }
        if (entitlement.expiresAt() == null) {
            return "READ_ONLY";
        }
        if (!now.isAfter(entitlement.expiresAt())) {
            return "ACTIVE";
        }
        if (!now.isAfter(entitlement.expiresAt().plus(WIND_DOWN_DAYS, ChronoUnit.DAYS))) {
            return "WIND_DOWN";
        }
        return "READ_ONLY";
    }

    public static boolean satisfiesDependency(Entitlement entitlement, Instant now) {
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        boolean formal = "FORMAL".equals(entitlement.licenseType())
                || "PLATFORM_GRANT".equals(entitlement.licenseType());
        return formal && "ACTIVE".equals(deriveStatus(entitlement, now));
    }

    private void applyInTransaction(long orderId) {
        OrderRow order = lockOrder(orderId);
        if ("FULFILLED".equals(order.status())) {
            return;
        }
        if (!"PAID".equals(order.status())) {
            throw stateConflict(order.status(), "FULFILLING");
        }

        Instant now = clock.now();
        int moved = jdbcClient.sql("""
                        UPDATE pf_subscription_order
                        SET status = 'FULFILLING',
                            fulfillment_attempts = fulfillment_attempts + 1,
                            last_fulfillment_error = NULL,
                            updated_at = :updatedAt,
                            version = version + 1
                        WHERE id = :orderId AND status = 'PAID'
                        """)
                .param("updatedAt", Timestamp.from(now))
                .param("orderId", orderId)
                .update();
        if (moved != 1) {
            throw stateConflict(order.status(), "FULFILLING");
        }

        boolean entitlementChanged = false;
        for (OrderItem item : loadItems(orderId)) {
            if (!"PENDING".equals(item.entitlementResult())) {
                continue;
            }
            entitlementChanged |= applyItem(order.enterpriseId(), orderId, item, now);
        }

        validateRequiredDependencies(order.enterpriseId(), orderId, now);

        if (entitlementChanged) {
            List<Long> instanceIds = jdbcClient.sql("""
                            SELECT id FROM pf_deployment_instance
                            WHERE enterprise_id = :enterpriseId
                            ORDER BY id
                            """)
                    .param("enterpriseId", order.enterpriseId())
                    .query(Long.class)
                    .list();
            for (Long instanceId : instanceIds) {
                licenseIssueService.issueForInstance(instanceId, "order:" + orderId);
            }
        }

        int fulfilled = jdbcClient.sql("""
                        UPDATE pf_subscription_order
                        SET status = 'FULFILLED', fulfilled_at = :fulfilledAt,
                            updated_at = :updatedAt, version = version + 1
                        WHERE id = :orderId AND status = 'FULFILLING'
                        """)
                .param("fulfilledAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .param("orderId", orderId)
                .update();
        if (fulfilled != 1) {
            throw stateConflict("FULFILLING", "FULFILLED");
        }
    }

    private boolean applyItem(long enterpriseId, long orderId, OrderItem item, Instant now) {
        ExistingEntitlement existing = findEntitlementForUpdate(enterpriseId, item.moduleId()).orElse(null);
        String billingMode = item.billingMode().toUpperCase(Locale.ROOT);
        if (existing != null && existing.perpetual()) {
            updateItemResult(item.id(), "SKIPPED_EXISTING");
            return false;
        }

        if (existing == null) {
            boolean perpetual = "PERPETUAL".equals(billingMode);
            Instant expiresAt = perpetual ? null : now.plus(item.durationDays(), ChronoUnit.DAYS);
            jdbcClient.sql("""
                            INSERT INTO pf_entitlement(
                                enterprise_id, module_id, license_type, status, starts_at,
                                expires_at, perpetual, store_quota, source_order_id,
                                version, created_at, updated_at
                            ) VALUES (
                                :enterpriseId, :moduleId, 'FORMAL', 'ACTIVE', :startsAt,
                                :expiresAt, :perpetual, :storeQuota, :orderId,
                                1, :now, :now
                            )
                            """)
                    .param("enterpriseId", enterpriseId)
                    .param("moduleId", item.moduleId())
                    .param("startsAt", Timestamp.from(now))
                    .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                    .param("perpetual", perpetual)
                    .param("storeQuota", item.storeQuota())
                    .param("orderId", orderId)
                    .param("now", Timestamp.from(now))
                    .update();
            updateItemResult(item.id(), "APPLIED");
            return true;
        }

        Instant startsAt = existing.startsAt() == null ? now : existing.startsAt();
        boolean perpetual = "PERPETUAL".equals(billingMode);
        String result;
        Instant expiresAt;
        if (perpetual) {
            expiresAt = null;
            result = "UPGRADED_PERPETUAL";
        } else {
            boolean activePeriod = existing.expiresAt() != null && existing.expiresAt().isAfter(now);
            Instant renewalBase = activePeriod ? existing.expiresAt() : now;
            if (!activePeriod) {
                startsAt = now;
            }
            expiresAt = renewalBase.plus(item.durationDays(), ChronoUnit.DAYS);
            result = activePeriod ? "RENEWED" : "APPLIED";
        }

        jdbcClient.sql("""
                        UPDATE pf_entitlement
                        SET license_type = 'FORMAL', status = 'ACTIVE', starts_at = :startsAt,
                            expires_at = :expiresAt, perpetual = :perpetual,
                            store_quota = :storeQuota, source_order_id = :orderId,
                            version = version + 1, updated_at = :updatedAt
                        WHERE id = :entitlementId
                        """)
                .param("startsAt", Timestamp.from(startsAt))
                .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .param("perpetual", perpetual)
                .param("storeQuota", item.storeQuota())
                .param("orderId", orderId)
                .param("updatedAt", Timestamp.from(now))
                .param("entitlementId", existing.id())
                .update();
        updateItemResult(item.id(), result);
        return true;
    }

    private void validateRequiredDependencies(long enterpriseId, long orderId, Instant now) {
        jdbcClient.sql("""
                        SELECT ordered.module_code, dependency.code AS dependency_code,
                               e.license_type, e.status, e.starts_at, e.expires_at, e.perpetual
                        FROM pf_subscription_order_item ordered
                        JOIN pf_module_dependency d
                          ON d.module_id = ordered.module_id AND d.dependency_type = 'REQUIRED'
                        JOIN pf_module_definition dependency ON dependency.id = d.dependency_module_id
                        LEFT JOIN pf_entitlement e
                          ON e.enterprise_id = :enterpriseId
                         AND e.module_id = d.dependency_module_id
                        WHERE ordered.order_id = :orderId
                        ORDER BY ordered.id, d.id
                        """)
                .param("enterpriseId", enterpriseId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new DependencyState(
                        rs.getString("module_code"),
                        rs.getString("dependency_code"),
                        dependencyEntitlement(rs)
                ))
                .list()
                .forEach(dependency -> {
                    if (dependency.entitlement() == null
                            || !satisfiesDependency(dependency.entitlement(), now)) {
                        throw new PlatformApiException(
                                ErrorCode.MODULE_DEPENDENCY_MISSING,
                                "模块 " + dependency.moduleCode()
                                        + " 缺少有效正式授权的依赖模块 "
                                        + dependency.dependencyCode()
                        );
                    }
                });
    }

    private static Entitlement dependencyEntitlement(ResultSet rs) throws SQLException {
        String licenseType = rs.getString("license_type");
        if (licenseType == null) {
            return null;
        }
        Timestamp startsAt = rs.getTimestamp("starts_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new Entitlement(
                0,
                0,
                0,
                licenseType,
                rs.getString("status"),
                startsAt == null ? null : startsAt.toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getBoolean("perpetual"),
                0,
                false
        );
    }

    private OrderRow lockOrder(long orderId) {
        return jdbcClient.sql("""
                        SELECT id, enterprise_id, status
                        FROM pf_subscription_order
                        WHERE id = :orderId
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getLong("id"),
                        rs.getLong("enterprise_id"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.ORDER_NOT_FOUND,
                        "销售订单不存在"
                ));
    }

    private List<OrderItem> loadItems(long orderId) {
        return jdbcClient.sql("""
                        SELECT id, module_id, billing_mode, duration_days, store_quota,
                               entitlement_result
                        FROM pf_subscription_order_item
                        WHERE order_id = :orderId
                        ORDER BY id
                        """)
                .param("orderId", orderId)
                .query(this::mapItem)
                .list();
    }

    private java.util.Optional<ExistingEntitlement> findEntitlementForUpdate(
            long enterpriseId,
            long moduleId
    ) {
        return jdbcClient.sql("""
                        SELECT id, license_type, status, starts_at, expires_at, perpetual,
                               store_quota, version
                        FROM pf_entitlement
                        WHERE enterprise_id = :enterpriseId AND module_id = :moduleId
                        FOR UPDATE
                        """)
                .param("enterpriseId", enterpriseId)
                .param("moduleId", moduleId)
                .query(this::mapExistingEntitlement)
                .optional();
    }

    private void updateItemResult(long itemId, String result) {
        int updated = jdbcClient.sql("""
                        UPDATE pf_subscription_order_item
                        SET entitlement_result = :result
                        WHERE id = :itemId AND entitlement_result = 'PENDING'
                        """)
                .param("result", result)
                .param("itemId", itemId)
                .update();
        if (updated != 1) {
            throw new PlatformApiException(ErrorCode.CONFLICT, "订单项履约状态已变化");
        }
    }

    private OrderItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new OrderItem(
                rs.getLong("id"),
                rs.getLong("module_id"),
                rs.getString("billing_mode"),
                rs.getInt("duration_days"),
                rs.getInt("store_quota"),
                rs.getString("entitlement_result")
        );
    }

    private ExistingEntitlement mapExistingEntitlement(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp startsAt = rs.getTimestamp("starts_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new ExistingEntitlement(
                rs.getLong("id"),
                rs.getString("license_type"),
                rs.getString("status"),
                startsAt == null ? null : startsAt.toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getBoolean("perpetual"),
                rs.getInt("store_quota"),
                rs.getLong("version")
        );
    }

    private static PlatformApiException stateConflict(String fromStatus, String toStatus) {
        return new PlatformApiException(
                ErrorCode.ORDER_STATE_CONFLICT,
                "订单状态不允许从 " + fromStatus + " 流转到 " + toStatus
        );
    }

    public record Entitlement(
            long enterpriseId,
            long instanceId,
            long moduleId,
            String licenseType,
            String status,
            Instant startsAt,
            Instant expiresAt,
            boolean perpetual,
            int storeQuota,
            boolean suspended
    ) {
    }

    private record OrderRow(long id, long enterpriseId, String status) {
    }

    private record OrderItem(
            long id,
            long moduleId,
            String billingMode,
            int durationDays,
            int storeQuota,
            String entitlementResult
    ) {
    }

    private record ExistingEntitlement(
            long id,
            String licenseType,
            String status,
            Instant startsAt,
            Instant expiresAt,
            boolean perpetual,
            int storeQuota,
            long version
    ) {
    }

    private record DependencyState(
            String moduleCode,
            String dependencyCode,
            Entitlement entitlement
    ) {
    }
}
