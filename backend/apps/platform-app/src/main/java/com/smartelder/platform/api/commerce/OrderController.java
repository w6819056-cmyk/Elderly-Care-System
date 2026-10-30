package com.smartelder.platform.api.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.commerce.OrderQuoteService;
import com.smartelder.platform.core.commerce.SalesOrderService;
import com.smartelder.platform.core.common.PlatformApiException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/orders")
public class OrderController {

    private final OrderQuoteService quoteService;
    private final SalesOrderService orderService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderController(
            OrderQuoteService quoteService,
            SalesOrderService orderService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.quoteService = quoteService;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('platform.order.view')")
    public ApiResponse<OrderQuoteService.QuoteView> quote(
            @RequestBody OrderRequest request,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        OrderQuoteService.QuoteView quote = quoteService.quote(toQuoteCommand(request));
        return ApiResponse.success(quote, traceId, clock);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.order.manage')")
    public ApiResponse<SalesOrderService.OrderView> create(
            @RequestBody String requestBody,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        OrderRequest request = parse(requestBody);
        SalesOrderService.OrderView order = orderService.create(
                new SalesOrderService.CreateOrderCommand(
                        request.enterpriseId(),
                        request.planCode(),
                        toQuoteItems(request.items()),
                        toBillingPeriod(request.period())
                ),
                principal,
                idempotencyKey,
                requestBody,
                traceId
        );
        return ApiResponse.success(order, traceId, clock);
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("hasAuthority('platform.order.view')")
    public ApiResponse<SalesOrderService.OrderView> get(
            @PathVariable String publicId,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(orderService.get(publicId), traceId, clock);
    }

    @PostMapping("/{publicId}/pay-simulated")
    @PreAuthorize("hasAuthority('platform.order.manage')")
    public ApiResponse<SalesOrderService.OrderView> paySimulated(
            @PathVariable String publicId,
            @RequestBody PayRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        SalesOrderService.OrderView order = orderService.paySimulated(
                publicId,
                new SalesOrderService.PayCommand(
                        request.channel(),
                        request.channelTransactionNo()
                ),
                principal,
                traceId
        );
        return ApiResponse.success(order, traceId, clock);
    }

    @PostMapping("/{publicId}/retry-fulfillment")
    @PreAuthorize("hasAuthority('platform.order.manage')")
    public ApiResponse<SalesOrderService.OrderView> retryFulfillment(
            @PathVariable String publicId,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                orderService.retryFulfillment(publicId, principal, traceId),
                traceId,
                clock
        );
    }

    @PostMapping("/{publicId}/refund")
    @PreAuthorize("hasAuthority('platform.order.manage')")
    public ApiResponse<SalesOrderService.OrderView> refund(
            @PathVariable String publicId,
            @RequestBody RefundRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        SalesOrderService.OrderView order = orderService.refund(
                publicId,
                new SalesOrderService.RefundCommand(request.reason()),
                principal,
                traceId
        );
        return ApiResponse.success(order, traceId, clock);
    }

    private OrderRequest parse(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "请求体不能为空");
        }
        try {
            OrderRequest request = objectMapper.readValue(requestBody, OrderRequest.class);
            if (request == null) {
                throw new PlatformApiException(ErrorCode.INVALID_REQUEST, "请求体不能为空");
            }
            return request;
        } catch (JsonProcessingException exception) {
            throw new PlatformApiException(
                    ErrorCode.INVALID_REQUEST,
                    "请求体格式错误",
                    exception
            );
        }
    }

    private static OrderQuoteService.QuoteCommand toQuoteCommand(OrderRequest request) {
        return new OrderQuoteService.QuoteCommand(
                request.enterpriseId(),
                request.planCode(),
                toQuoteItems(request.items()),
                toBillingPeriod(request.period())
        );
    }

    private static List<OrderQuoteService.QuoteItem> toQuoteItems(List<QuoteItemRequest> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(item -> new OrderQuoteService.QuoteItem(
                        item.moduleCode(),
                        item.billingMode(),
                        item.durationDays(),
                        item.storeQuota()
                ))
                .toList();
    }

    private static OrderQuoteService.BillingPeriod toBillingPeriod(BillingPeriodRequest period) {
        if (period == null) {
            return null;
        }
        return new OrderQuoteService.BillingPeriod(
                period.billingMode(),
                period.durationDays(),
                period.storeQuota()
        );
    }

    public record OrderRequest(
            String enterpriseId,
            String planCode,
            List<QuoteItemRequest> items,
            BillingPeriodRequest period
    ) {
    }

    public record QuoteItemRequest(
            String moduleCode,
            String billingMode,
            Integer durationDays,
            Integer storeQuota
    ) {
    }

    public record BillingPeriodRequest(
            String billingMode,
            Integer durationDays,
            Integer storeQuota
    ) {
    }

    public record PayRequest(String channel, String channelTransactionNo) {
    }

    public record RefundRequest(String reason) {
    }
}
