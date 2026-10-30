package com.smartelder.platform.core.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.IdempotencyService;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import com.smartelder.platform.core.common.PublicIds;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class SalesOrderService {

    private static final String CREATE_SCOPE = "platform.order.create";
    private static final String CREATE_PATH = "/api/platform/v1/orders";
    private static final Set<String> PAYMENT_CHANNELS = Set.of("MOCK");
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "DRAFT", Set.of("PENDING_PAYMENT"),
            "PENDING_PAYMENT", Set.of("PAID", "PAYMENT_FAILED", "CANCELLED"),
            "PAID", Set.of("FULFILLING", "MANUAL_REVIEW", "REFUND_PENDING"),
            "FULFILLING", Set.of("FULFILLED", "MANUAL_REVIEW"),
            "FULFILLED", Set.of("REFUND_PENDING"),
            "REFUND_PENDING", Set.of("REFUNDED")
    );

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final PlatformClock clock;
    private final PlatformAuditService auditService;
    private final OrderQuoteService quoteService;
    private final IdempotencyService idempotencyService;
    private final EntitlementService entitlementService;
    private final TransactionTemplate transactionTemplate;

    public SalesOrderService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            PlatformClock clock,
            PlatformAuditService auditService,
            OrderQuoteService quoteService,
            IdempotencyService idempotencyService,
            EntitlementService entitlementService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.quoteService = Objects.requireNonNull(quoteService, "quoteService must not be null");
        this.idempotencyService = Objects.requireNonNull(
                idempotencyService,
                "idempotencyService must not be null"
        );
        this.entitlementService = Objects.requireNonNull(
                entitlementService,
                "entitlementService must not be null"
        );
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    public OrderView create(
            CreateOrderCommand command,
            PlatformPrincipal actor,
            String idempotencyKey,
            String requestBody,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        String key = requireText(idempotencyKey, "Idempotency-Key", 100);
        String body = requestBody == null ? "" : requestBody;
        return idempotencyService.execute(
                CREATE_SCOPE,
                key,
                "POST",
                CREATE_PATH,
                body,
                () -> createInternal(command, actor, body, traceId),
                OrderView.class
        );
    }

    public OrderView get(String publicId) {
        return loadOrder(requireText(publicId, "publicId", 64), false);
    }

    public OrderView retryFulfillment(
            String publicId,
            PlatformPrincipal actor,
            String traceId
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        String normalizedPublicId = requireText(publicId, "publicId", 64);
        OrderRow order = loadOrderRow(
                jdbcClient.sql("SELECT id FROM pf_subscription_order WHERE public_id = :publicId")
                        .param("publicId", normalizedPublicId)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> new PlatformApiException(
                                ErrorCode.ORDER_NOT_FOUND,
                                "销售订单不存在"
                        )),
                false
        );
        entitlementService.retryFulfillment(order.id(), traceId);
        auditService.record(
                actor.userId(),
                actor.publicId(),
                "ORDER_FULFILLMENT_RETRIED",
                "SUBSCRIPTION_ORDER",
                normalizedPublicId,
                toJson(Map.of("status", loadOrder(normalizedPublicId, false).status())),
                traceId
        );
        return loadOrder(normalizedPublicId, false);
    }

    public OrderView paySimulated(
            String publicId,
            PayCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        String normalizedPublicId = requireText(publicId, "publicId", 64);
        String channel = requireText(command.channel(), "channel", 30).toUpperCase(Locale.ROOT);
        String channelTransactionNo = requireText(
                command.channelTransactionNo(),
                "channelTransactionNo",
                100
        );
        if (!PAYMENT_CHANNELS.contains(channel)) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "当前仅支持 MOCK 模拟支付渠道"
            );
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            OrderRow order = lockOrder(normalizedPublicId);
            Optional<PaymentRow> existingPayment = findPayment(channelTransactionNo);
            if (existingPayment.isPresent()) {
                PaymentRow payment = existingPayment.orElseThrow();
                if (payment.orderId() != order.id()) {
                    throw new PlatformApiException(
                            ErrorCode.CONFLICT,
                            "渠道交易号已被其他订单使用"
                    );
                }
                entitlementService.applyPaidOrder(order.id());
                return loadOrder(normalizedPublicId, false);
            }
            ensureTransition(order.status(), "PAID");
            Instant now = clock.now();
            try {
                jdbcClient.sql("""
                                INSERT INTO pf_payment_transaction(
                                    public_id, order_id, channel, channel_transaction_no,
                                    amount, status, paid_at, created_at
                                ) VALUES (
                                    :publicId, :orderId, :channel, :channelTransactionNo,
                                    :amount, 'SUCCESS', :paidAt, :createdAt
                                )
                                """)
                        .param("publicId", PublicIds.newId("pay"))
                        .param("orderId", order.id())
                        .param("channel", channel)
                        .param("channelTransactionNo", channelTransactionNo)
                        .param("amount", order.payableAmount())
                        .param("paidAt", Timestamp.from(now))
                        .param("createdAt", Timestamp.from(now))
                        .update();
            } catch (DuplicateKeyException exception) {
                throw new PlatformApiException(
                        ErrorCode.CONFLICT,
                        "渠道交易号已被使用",
                        exception
                );
            }
            transition(order, "PAID", "paid_at", now);
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "ORDER_PAID_SIMULATED",
                    "SUBSCRIPTION_ORDER",
                    order.publicId(),
                    toJson(Map.of(
                            "channel", channel,
                            "channelTransactionNo", channelTransactionNo,
                            "amount", order.payableAmount().toPlainString()
                    )),
                    traceId
            );
            entitlementService.applyPaidOrder(order.id());
            return loadOrder(normalizedPublicId, false);
        }), "paySimulated must return an order");
    }

    public OrderView refund(
            String publicId,
            RefundCommand command,
            PlatformPrincipal actor,
            String traceId
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        String normalizedPublicId = requireText(publicId, "publicId", 64);
        String reason = requireText(command.reason(), "reason", 500);

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            OrderRow order = lockOrder(normalizedPublicId);
            ensureTransition(order.status(), "REFUND_PENDING");
            Instant now = clock.now();
            transition(order, "REFUND_PENDING", null, now);
            String refundPublicId = PublicIds.newId("refund");
            jdbcClient.sql("""
                            INSERT INTO pf_refund(
                                public_id, order_id, amount, reason, status, created_at
                            ) VALUES (
                                :publicId, :orderId, :amount, :reason, 'COMPLETED', :createdAt
                            )
                            """)
                    .param("publicId", refundPublicId)
                    .param("orderId", order.id())
                    .param("amount", order.payableAmount())
                    .param("reason", reason)
                    .param("createdAt", Timestamp.from(now))
                    .update();
            transition(loadOrderRow(order.id(), true), "REFUNDED", null, now);
            auditService.record(
                    actor.userId(),
                    actor.publicId(),
                    "ORDER_REFUNDED",
                    "SUBSCRIPTION_ORDER",
                    order.publicId(),
                    toJson(Map.of(
                            "refundId", refundPublicId,
                            "amount", order.payableAmount().toPlainString(),
                            "reason", reason
                    )),
                    traceId
            );
            return loadOrder(normalizedPublicId, false);
        }), "refund must return an order");
    }

    private OrderView createInternal(
            CreateOrderCommand command,
            PlatformPrincipal actor,
            String requestBody,
            String traceId
    ) {
        String enterpriseId = requireText(command.enterpriseId(), "enterpriseId", 64);
        String planCode = trimToNull(command.planCode());
        List<OrderQuoteService.QuoteItem> items = command.items() == null
                ? List.of()
                : List.copyOf(command.items());
        OrderQuoteService.QuoteView quote = quoteService.quote(
                new OrderQuoteService.QuoteCommand(
                        enterpriseId,
                        planCode,
                        items,
                        command.period()
                )
        );
        long enterprisePk = findEnterprisePk(enterpriseId);
        Long planId = planCode == null ? null : findPlanId(planCode);
        Instant now = clock.now();
        String publicId = PublicIds.newId("order");
        String orderNo = "PO-" + publicId.substring("order_".length()).toUpperCase(Locale.ROOT);

        jdbcClient.sql("""
                        INSERT INTO pf_subscription_order(
                            public_id, order_no, enterprise_id, plan_id, status, currency,
                            original_amount, deduction_amount, payable_amount, request_hash,
                            created_by, paid_at, fulfilled_at, fulfillment_attempts,
                            last_fulfillment_error, created_at, updated_at, version
                        ) VALUES (
                            :publicId, :orderNo, :enterpriseId, :planId, 'PENDING_PAYMENT', :currency,
                            :originalAmount, :deductionAmount, :payableAmount, :requestHash,
                            :createdBy, NULL, NULL, 0,
                            NULL, :createdAt, :updatedAt, 0
                        )
                        """)
                .param("publicId", publicId)
                .param("orderNo", orderNo)
                .param("enterpriseId", enterprisePk)
                .param("planId", planId, Types.BIGINT)
                .param("currency", quote.currency())
                .param("originalAmount", quote.originalAmount())
                .param("deductionAmount", quote.deductionAmount())
                .param("payableAmount", quote.payableAmount())
                .param("requestHash", sha256(requestBody))
                .param("createdBy", actor.userId())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();

        long orderPk = jdbcClient.sql(
                        "SELECT id FROM pf_subscription_order WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query(Long.class)
                .single();
        for (OrderQuoteService.QuoteItemView item : quote.items()) {
            jdbcClient.sql("""
                            INSERT INTO pf_subscription_order_item(
                                order_id, module_id, module_code, module_name, billing_mode,
                                duration_days, store_quota, standard_amount, deduction_amount,
                                actual_amount, entitlement_result
                            ) VALUES (
                                :orderId, :moduleId, :moduleCode, :moduleName, :billingMode,
                                :durationDays, :storeQuota, :standardAmount, :deductionAmount,
                                :actualAmount, :entitlementResult
                            )
                            """)
                    .param("orderId", orderPk)
                    .param("moduleId", item.moduleId())
                    .param("moduleCode", item.moduleCode())
                    .param("moduleName", item.moduleName())
                    .param("billingMode", item.billingMode())
                    .param("durationDays", item.durationDays())
                    .param("storeQuota", item.storeQuota())
                    .param("standardAmount", item.standardAmount())
                    .param("deductionAmount", item.deductionAmount())
                    .param("actualAmount", item.actualAmount())
                    .param("entitlementResult", item.entitlementEffect()
                            ? "PENDING"
                            : "SKIPPED_EXISTING")
                    .update();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planCode", planCode);
        detail.put("itemCount", quote.items().size());
        detail.put("payableAmount", quote.payableAmount().toPlainString());
        auditService.record(
                actor.userId(),
                actor.publicId(),
                "ORDER_CREATED",
                "SUBSCRIPTION_ORDER",
                publicId,
                toJson(detail),
                traceId
        );
        return loadOrder(publicId, false);
    }

    private OrderRow lockOrder(String publicId) {
        return jdbcClient.sql(orderSelect() + " WHERE o.public_id = :publicId FOR UPDATE")
                .param("publicId", publicId)
                .query(this::mapOrder)
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.ORDER_NOT_FOUND,
                        "销售订单不存在"
                ));
    }

    private OrderRow loadOrderRow(long orderId, boolean forUpdate) {
        String sql = orderSelect() + " WHERE o.id = :orderId" + (forUpdate ? " FOR UPDATE" : "");
        return jdbcClient.sql(sql)
                .param("orderId", orderId)
                .query(this::mapOrder)
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.ORDER_NOT_FOUND,
                        "销售订单不存在"
                ));
    }

    private OrderView loadOrder(String publicId, boolean forUpdate) {
        String sql = orderSelect() + " WHERE o.public_id = :publicId"
                + (forUpdate ? " FOR UPDATE" : "");
        OrderRow order = jdbcClient.sql(sql)
                .param("publicId", publicId)
                .query(this::mapOrder)
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.ORDER_NOT_FOUND,
                        "销售订单不存在"
                ));
        List<OrderItemView> items = jdbcClient.sql("""
                        SELECT module_code, module_name, billing_mode, duration_days, store_quota,
                               standard_amount, deduction_amount, actual_amount, entitlement_result
                        FROM pf_subscription_order_item
                        WHERE order_id = :orderId
                        ORDER BY id
                        """)
                .param("orderId", order.id())
                .query((rs, rowNum) -> new OrderItemView(
                        rs.getString("module_code"),
                        rs.getString("module_name"),
                        rs.getString("billing_mode"),
                        rs.getInt("duration_days"),
                        rs.getInt("store_quota"),
                        rs.getBigDecimal("standard_amount"),
                        rs.getBigDecimal("deduction_amount"),
                        rs.getBigDecimal("actual_amount"),
                        rs.getString("entitlement_result")
                ))
                .list();
        List<PaymentView> payments = jdbcClient.sql("""
                        SELECT public_id, channel, channel_transaction_no, amount, status, paid_at
                        FROM pf_payment_transaction
                        WHERE order_id = :orderId
                        ORDER BY id
                        """)
                .param("orderId", order.id())
                .query((rs, rowNum) -> new PaymentView(
                        rs.getString("public_id"),
                        rs.getString("channel"),
                        rs.getString("channel_transaction_no"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        instant(rs, "paid_at")
                ))
                .list();
        List<RefundView> refunds = jdbcClient.sql("""
                        SELECT public_id, amount, reason, status, created_at
                        FROM pf_refund
                        WHERE order_id = :orderId
                        ORDER BY id
                        """)
                .param("orderId", order.id())
                .query((rs, rowNum) -> new RefundView(
                        rs.getString("public_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        instant(rs, "created_at")
                ))
                .list();
        return new OrderView(
                order.publicId(),
                order.orderNo(),
                order.enterpriseId(),
                order.planCode(),
                order.status(),
                order.currency(),
                order.originalAmount(),
                order.deductionAmount(),
                order.payableAmount(),
                order.createdAt(),
                order.paidAt(),
                order.fulfilledAt(),
                items,
                payments,
                refunds
        );
    }

    private String orderSelect() {
        return """
                SELECT o.id, o.public_id, o.order_no, e.public_id AS enterprise_public_id,
                       p.code AS plan_code, o.status, o.currency, o.original_amount,
                       o.deduction_amount, o.payable_amount, o.created_at, o.paid_at,
                       o.fulfilled_at
                FROM pf_subscription_order o
                JOIN pf_enterprise e ON e.id = o.enterprise_id
                LEFT JOIN pf_plan p ON p.id = o.plan_id
                """;
    }

    private OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getString("public_id"),
                rs.getString("order_no"),
                rs.getString("enterprise_public_id"),
                rs.getString("plan_code"),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("deduction_amount"),
                rs.getBigDecimal("payable_amount"),
                instant(rs, "created_at"),
                instant(rs, "paid_at"),
                instant(rs, "fulfilled_at")
        );
    }

    private Optional<PaymentRow> findPayment(String channelTransactionNo) {
        return jdbcClient.sql("""
                        SELECT id, order_id, status
                        FROM pf_payment_transaction
                        WHERE channel_transaction_no = :channelTransactionNo
                        """)
                .param("channelTransactionNo", channelTransactionNo)
                .query((rs, rowNum) -> new PaymentRow(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getString("status")
                ))
                .optional();
    }

    private void transition(OrderRow order, String toStatus, String timestampColumn, Instant now) {
        ensureTransition(order.status(), toStatus);
        String timestampUpdate = timestampColumn == null ? "" : ", " + timestampColumn + " = :timestamp\n";
        int updated = jdbcClient.sql("""
                        UPDATE pf_subscription_order
                        SET status = :toStatus, updated_at = :updatedAt, version = version + 1
                        """ + timestampUpdate + """
                        WHERE id = :orderId AND status = :fromStatus
                        """)
                .param("toStatus", toStatus)
                .param("updatedAt", Timestamp.from(now))
                .param("timestamp", Timestamp.from(now), Types.TIMESTAMP)
                .param("orderId", order.id())
                .param("fromStatus", order.status())
                .update();
        if (updated != 1) {
            throw stateConflict(order.status(), toStatus);
        }
    }

    private static void ensureTransition(String fromStatus, String toStatus) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(fromStatus, Set.of());
        if (!allowed.contains(toStatus)) {
            throw stateConflict(fromStatus, toStatus);
        }
    }

    private static PlatformApiException stateConflict(String fromStatus, String toStatus) {
        return new PlatformApiException(
                ErrorCode.ORDER_STATE_CONFLICT,
                "订单状态不允许从 " + fromStatus + " 流转到 " + toStatus
        );
    }

    private long findEnterprisePk(String enterpriseId) {
        return jdbcClient.sql("SELECT id FROM pf_enterprise WHERE public_id = :publicId")
                .param("publicId", enterpriseId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "企业不存在"));
    }

    private long findPlanId(String planCode) {
        return jdbcClient.sql("SELECT id FROM pf_plan WHERE code = :code")
                .param("code", planCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new PlatformApiException(
                        ErrorCode.PLAN_NOT_FOUND,
                        "套餐不存在"
                ));
    }

    private String toJson(Map<String, ?> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize order audit detail", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, field + " 不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    field + " 长度不能超过 " + maxLength
            );
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record OrderRow(
            long id,
            String publicId,
            String orderNo,
            String enterpriseId,
            String planCode,
            String status,
            String currency,
            BigDecimal originalAmount,
            BigDecimal deductionAmount,
            BigDecimal payableAmount,
            Instant createdAt,
            Instant paidAt,
            Instant fulfilledAt
    ) {
    }

    private record PaymentRow(long id, long orderId, String status) {
    }

    public record CreateOrderCommand(
            String enterpriseId,
            String planCode,
            List<OrderQuoteService.QuoteItem> items,
            OrderQuoteService.BillingPeriod period
    ) {
    }

    public record PayCommand(String channel, String channelTransactionNo) {
    }

    public record RefundCommand(String reason) {
    }

    public record OrderItemView(
            String moduleCode,
            String moduleName,
            String billingMode,
            int durationDays,
            int storeQuota,
            BigDecimal standardAmount,
            BigDecimal deductionAmount,
            BigDecimal actualAmount,
            String entitlementResult
    ) {
    }

    public record PaymentView(
            String publicId,
            String channel,
            String channelTransactionNo,
            BigDecimal amount,
            String status,
            Instant paidAt
    ) {
    }

    public record RefundView(
            String publicId,
            BigDecimal amount,
            String reason,
            String status,
            Instant createdAt
    ) {
    }

    public record OrderView(
            String publicId,
            String orderNo,
            String enterpriseId,
            String planCode,
            String status,
            String currency,
            BigDecimal originalAmount,
            BigDecimal deductionAmount,
            BigDecimal payableAmount,
            Instant createdAt,
            Instant paidAt,
            Instant fulfilledAt,
            List<OrderItemView> items,
            List<PaymentView> payments,
            List<RefundView> refunds
    ) {
    }
}
