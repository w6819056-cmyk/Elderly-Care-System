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
                () -> createInternal(command, new OrderActor(actor.userId(),actor.publicId()), body, traceId),
                OrderView.class
        );
    }

    public OrderView get(String publicId) {
        return loadOrder(requireText(publicId, "publicId", 64), false);
    }

    public PageResult<OrderView> list(String enterpriseId, String status, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "page 必须大于 0，pageSize 必须介于 1 和 100 之间");
        }
        String enterpriseFilter = trimToNull(enterpriseId);
        String statusFilter = trimToNull(status);
        if (statusFilter != null && !Set.of("PENDING_PAYMENT", "PAID", "FULFILLING", "FULFILLED", "FULFILLMENT_FAILED", "MANUAL_REVIEW", "REFUND_PENDING", "REFUNDED", "CANCELLED").contains(statusFilter)) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "订单状态无效");
        }
        String where = " WHERE 1=1" + (enterpriseFilter == null ? "" : " AND e.public_id = :enterpriseId") + (statusFilter == null ? "" : " AND o.status = :status");
        var countQuery = jdbcClient.sql("SELECT COUNT(*) FROM pf_subscription_order o JOIN pf_enterprise e ON e.id=o.enterprise_id" + where);
        var pageQuery = jdbcClient.sql("SELECT o.public_id" + " FROM pf_subscription_order o JOIN pf_enterprise e ON e.id=o.enterprise_id" + where + " ORDER BY o.created_at DESC,o.id DESC LIMIT :limit OFFSET :offset");
        if (enterpriseFilter != null) { countQuery = countQuery.param("enterpriseId", enterpriseFilter); pageQuery = pageQuery.param("enterpriseId", enterpriseFilter); }
        if (statusFilter != null) { countQuery = countQuery.param("status", statusFilter); pageQuery = pageQuery.param("status", statusFilter); }
        long total = countQuery.query(Long.class).single();
        List<OrderView> items = pageQuery.param("limit", pageSize).param("offset", (page - 1) * pageSize)
                .query(String.class).list().stream().map(id -> loadOrder(id, false)).toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public OrderView editPending(String publicId, CreateOrderCommand command, PlatformPrincipal actor, String requestBody, String traceId) {
        String normalizedPublicId = requireText(publicId, "publicId", 64);
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            OrderRow order = lockOrder(normalizedPublicId);
            if(jdbcClient.sql("SELECT COUNT(*) FROM pf_sale_coupon WHERE reserved_order_id=:id AND status='RESERVED'").param("id",order.id()).query(Long.class).single()>0) {
                throw new PlatformApiException(ErrorCode.CONFLICT,"使用优惠券的订单请取消后重新下单，不能直接修改金额");
            }
            if (!"PENDING_PAYMENT".equals(order.status())) {
                throw stateConflict(order.status(), "EDITED");
            }
            if (!order.enterpriseId().equals(requireText(command.enterpriseId(), "enterpriseId", 64))) {
                throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "不能将订单转移到其他企业");
            }
            String planCode = trimToNull(command.planCode());
            OrderQuoteService.QuoteView quote = quoteService.quote(new OrderQuoteService.QuoteCommand(
                    order.enterpriseId(), planCode, command.items() == null ? List.of() : List.copyOf(command.items()), command.period()));
            Long planId = planCode == null ? null : findPlanId(planCode);
            Instant now = clock.now();
            jdbcClient.sql("UPDATE pf_subscription_order SET plan_id=:planId,currency=:currency,original_amount=:originalAmount,deduction_amount=:deductionAmount,payable_amount=:payableAmount,request_hash=:requestHash,updated_at=:updatedAt,version=version+1 WHERE id=:orderId AND status='PENDING_PAYMENT'")
                    .param("planId", planId, Types.BIGINT).param("currency", quote.currency())
                    .param("originalAmount", quote.originalAmount()).param("deductionAmount", quote.deductionAmount())
                    .param("payableAmount", quote.payableAmount()).param("requestHash", sha256(requestBody == null ? "" : requestBody))
                    .param("updatedAt", Timestamp.from(now)).param("orderId", order.id()).update();
            jdbcClient.sql("DELETE FROM pf_subscription_order_item WHERE order_id=:orderId").param("orderId", order.id()).update();
            insertOrderItems(order.id(), quote);
            auditService.record(actor.userId(), actor.publicId(), "ORDER_PENDING_EDITED", "SUBSCRIPTION_ORDER", normalizedPublicId,
                    toJson(Map.of("planCode", planCode == null ? "" : planCode, "itemCount", quote.items().size(), "payableAmount", quote.payableAmount().toPlainString())), traceId);
            return loadOrder(normalizedPublicId, false);
        }), "editPending must return an order");
    }

    public OrderView changeModuleAuthorization(String publicId, String moduleCode, String reason, boolean restore,
                                               PlatformPrincipal actor, String traceId) {
        String normalizedPublicId = requireText(publicId, "publicId", 64);
        String normalizedCode = requireText(moduleCode, "moduleCode", 40);
        String normalizedReason = requireText(reason, "reason", 500);
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            OrderRow order = lockOrder(normalizedPublicId);
            if (!"FULFILLED".equals(order.status())) {
                throw stateConflict(order.status(), restore ? "RESTORED" : "REVOKED");
            }
            long orderId = jdbcClient.sql("SELECT id FROM pf_subscription_order WHERE public_id=:publicId")
                    .param("publicId", normalizedPublicId).query(Long.class).single();
            OrderAuthorizationItem item = jdbcClient.sql("""
                            SELECT id, entitlement_result, revoked_from_entitlement_result
                            FROM pf_subscription_order_item
                            WHERE order_id=:orderId AND module_code=:moduleCode
                            FOR UPDATE
                            """)
                    .param("orderId", orderId)
                    .param("moduleCode", normalizedCode)
                    .query((rs, rowNum) -> new OrderAuthorizationItem(
                            rs.getLong("id"),
                            rs.getString("entitlement_result"),
                            rs.getString("revoked_from_entitlement_result")
                    ))
                    .optional()
                    .orElseThrow(() -> new PlatformApiException(ErrorCode.NOT_FOUND, "订单中不存在该模块"));

            String previousResult;
            String nextResult;
            if (restore) {
                if (!"REVOKED".equals(item.entitlementResult()) || item.revokedFromResult() == null) {
                    throw new PlatformApiException(ErrorCode.ORDER_STATE_CONFLICT, "该模块授权当前未被此订单撤销");
                }
                previousResult = item.revokedFromResult();
                nextResult = previousResult;
            } else {
                if (!Set.of("APPLIED", "RENEWED", "UPGRADED_PERPETUAL").contains(item.entitlementResult())) {
                    throw new PlatformApiException(ErrorCode.ORDER_STATE_CONFLICT, "该订单项没有可撤销的有效模块授权");
                }
                previousResult = item.entitlementResult();
                nextResult = "REVOKED";
            }

            long enterprisePk = findEnterprisePk(order.enterpriseId());
            long entitlementId = jdbcClient.sql("""
                            SELECT e.id
                            FROM pf_entitlement e
                            JOIN pf_module_definition m ON m.id=e.module_id
                            WHERE e.enterprise_id=:enterpriseId AND m.code=:moduleCode AND e.source_order_id=:orderId
                            FOR UPDATE
                            """)
                    .param("enterpriseId", enterprisePk)
                    .param("moduleCode", normalizedCode)
                    .param("orderId", orderId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new PlatformApiException(
                            ErrorCode.ORDER_STATE_CONFLICT,
                            "该订单已不再是此模块授权的当前来源"
                    ));

            jdbcClient.sql("UPDATE pf_entitlement SET status=:status,version=version+1,updated_at=:now WHERE id=:id")
                    .param("status", restore ? "ACTIVE" : "SUSPENDED")
                    .param("now", Timestamp.from(clock.now()))
                    .param("id", entitlementId)
                    .update();
            jdbcClient.sql("""
                            UPDATE pf_subscription_order_item
                            SET entitlement_result=:result, revoked_from_entitlement_result=:revokedFrom
                            WHERE id=:id
                            """)
                    .param("result", nextResult)
                    .param("revokedFrom", restore ? null : previousResult, Types.VARCHAR)
                    .param("id", item.id())
                    .update();
            auditService.record(
                    actor.userId(), actor.publicId(),
                    restore ? "ORDER_MODULE_AUTHORIZATION_RESTORED" : "ORDER_MODULE_AUTHORIZATION_REVOKED",
                    "SUBSCRIPTION_ORDER_ITEM", normalizedPublicId,
                    toJson(Map.of("moduleCode", normalizedCode, "reason", normalizedReason)), traceId
            );
            jdbcClient.sql("SELECT id FROM pf_deployment_instance WHERE enterprise_id=:enterpriseId ORDER BY id")
                    .param("enterpriseId", enterprisePk)
                    .query(Long.class)
                    .list()
                    .forEach(id -> entitlementService.issueLicenseForInstance(id, traceId));
            return loadOrder(normalizedPublicId, false);
        }), "changeModuleAuthorization must return an order");
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
        return paySimulatedAs(publicId,command,new OrderActor(actor.userId(),actor.publicId()),traceId);
    }
    private OrderView paySimulatedAs(String publicId,PayCommand command,OrderActor actor,String traceId) {
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
            jdbcClient.sql("UPDATE pf_sale_coupon SET status='USED' WHERE reserved_order_id=:id AND status='RESERVED'").param("id",order.id()).update();
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
            OrderActor actor,
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
        String couponCode = couponCode(requestBody);
        if (couponCode != null) quote = applyCoupon(quote, couponCode);
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
                .param("createdBy", actor.userId(), Types.BIGINT)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();

        long orderPk = jdbcClient.sql(
                        "SELECT id FROM pf_subscription_order WHERE public_id = :publicId")
                .param("publicId", publicId)
                .query(Long.class)
                .single();
        insertOrderItems(orderPk, quote);
        if (couponCode != null) {
            int reserved = jdbcClient.sql("UPDATE pf_sale_coupon SET status='RESERVED',reserved_order_id=:id WHERE code=:code AND status='AVAILABLE'")
                    .param("id",orderPk).param("code",couponCode).update();
            if(reserved!=1)throw new PlatformApiException(ErrorCode.CONFLICT,"优惠券已被其他订单占用");
            jdbcClient.sql("UPDATE pf_subscription_order SET coupon_code=:code WHERE id=:id").param("code",couponCode).param("id",orderPk).update();
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

    public OrderView cancelPending(String publicId,PlatformPrincipal actor,String traceId) {
        return cancelPendingAs(publicId,new OrderActor(actor.userId(),actor.publicId()),traceId);
    }
    private OrderView cancelPendingAs(String publicId,OrderActor actor,String traceId) {
        return Objects.requireNonNull(transactionTemplate.execute(tx -> {
            OrderRow order=lockOrder(requireText(publicId,"publicId",64));
            transition(order,"CANCELLED",null,clock.now());
            jdbcClient.sql("UPDATE pf_sale_coupon SET status='AVAILABLE',reserved_order_id=NULL WHERE reserved_order_id=:id AND status='RESERVED'").param("id",order.id()).update();
            auditService.record(actor.userId(),actor.publicId(),"ORDER_CANCELLED","SUBSCRIPTION_ORDER",publicId,null,traceId);
            return loadOrder(publicId,false);
        }));
    }

    private String couponCode(String body) {
        try {var json=objectMapper.readTree(body); if(json==null)return null; String value=json.path("couponCode").asText("").trim();return value.isEmpty()?null:requireText(value,"couponCode",64);}
        catch(JsonProcessingException e){throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"请求格式错误",e);}
    }

    private OrderQuoteService.QuoteView applyCoupon(OrderQuoteService.QuoteView quote,String code) {
        var coupon=jdbcClient.sql("SELECT type,coupon_value,minimum_amount FROM pf_sale_coupon WHERE code=:code AND status='AVAILABLE' AND expires_at>:now FOR UPDATE")
                .param("code",code).param("now",Timestamp.from(clock.now())).query().listOfRows().stream().findFirst()
                .orElseThrow(()->new PlatformApiException(ErrorCode.CONFLICT,"优惠券不可用、已过期或已被占用"));
        BigDecimal base=quote.payableAmount();
        if(base.compareTo((BigDecimal)coupon.get("minimum_amount"))<0)throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"未达到优惠券最低消费");
        BigDecimal value=(BigDecimal)coupon.get("coupon_value");
        BigDecimal after="DISCOUNT".equals(coupon.get("type"))?base.multiply(value).divide(new BigDecimal("100"),2,java.math.RoundingMode.HALF_UP):base.subtract(value).max(BigDecimal.ZERO);
        BigDecimal remaining=base.subtract(after);
        List<OrderQuoteService.QuoteItemView> adjusted=new ArrayList<>();
        for(var item:quote.items()){
            BigDecimal discount=item.actualAmount().min(remaining);remaining=remaining.subtract(discount);
            adjusted.add(new OrderQuoteService.QuoteItemView(item.moduleId(),item.moduleCode(),item.moduleName(),item.billingMode(),item.durationDays(),item.storeQuota(),item.currency(),item.standardAmount(),item.deductionAmount().add(discount),item.actualAmount().subtract(discount),item.currentExpiresAt(),item.projectedExpiresAt(),item.entitlementEffect()));
        }
        return new OrderQuoteService.QuoteView(quote.enterpriseId(),quote.planCode(),quote.currency(),quote.originalAmount(),quote.deductionAmount().add(base.subtract(after)),after,List.copyOf(adjusted));
    }

    private record OrderActor(Long userId,String publicId) {}

    public OrderQuoteService.QuoteView quoteForEnterprise(CreateOrderCommand command,String couponCode) {
        return transactionTemplate.execute(tx -> {
            var quote=quoteService.quote(new OrderQuoteService.QuoteCommand(command.enterpriseId(),command.planCode(),command.items(),command.period()));
            return couponCode==null||couponCode.isBlank()?quote:applyCoupon(quote,requireText(couponCode,"couponCode",64));
        });
    }
    public OrderView createFromInstance(CreateOrderCommand command,long instanceId,String instancePublicId,String key,String body,String traceId) {
        String normalized=requireText(key,"Idempotency-Key",80);
        return idempotencyService.execute("enterprise.order."+command.enterpriseId(),normalized,"POST","/enterprise/orders",body,()->{
            var order=createInternal(command,new OrderActor(null,"INSTANCE:"+instancePublicId),body,traceId);
            jdbcClient.sql("UPDATE pf_subscription_order SET source_instance_id=:instance WHERE public_id=:id").param("instance",instanceId).param("id",order.publicId()).update();
            return order;
        },OrderView.class);
    }
    public OrderView getForEnterprise(String id,String enterpriseId) {
        OrderView order=get(id);
        if(!order.enterpriseId().equals(enterpriseId))throw new PlatformApiException(ErrorCode.ORDER_NOT_FOUND,"销售订单不存在");
        return order;
    }
    public OrderView payFromInstance(String id,String enterpriseId,String instancePublicId,String traceId) {
        getForEnterprise(id,enterpriseId);
        return paySimulatedAs(id,new PayCommand("MOCK","INSTANCE-MOCK-"+id),new OrderActor(null,"INSTANCE:"+instancePublicId),traceId);
    }
    public OrderView cancelFromInstance(String id,String enterpriseId,String instancePublicId,String traceId) {
        getForEnterprise(id,enterpriseId);
        return cancelPendingAs(id,new OrderActor(null,"INSTANCE:"+instancePublicId),traceId);
    }

    private void insertOrderItems(long orderPk, OrderQuoteService.QuoteView quote) {
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

    private record OrderAuthorizationItem(long id, String entitlementResult, String revokedFromResult) {
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

    public record PageResult<T>(List<T> items, long total, int page, int pageSize) {}
}
