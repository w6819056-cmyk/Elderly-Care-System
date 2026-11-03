package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.dto.ServiceDtos;
import com.smartelder.entity.WorkOrder;
import com.smartelder.entity.WorkOrderLog;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "服务调度", description = "服务商城、下单、派单、打卡、评价（SV-01~SV-15）")
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "服务商城列表（SV-09）")
    @GetMapping("/public/services")
    public Result<List<ServiceDtos.ItemView>> items(@RequestParam(required = false) String category) {
        return Result.ok(orderService.listOnSaleItems(category));
    }

    @Operation(summary = "下单（SV-01/SV-10，家属代下单传 elderId）")
    @PostMapping("/orders")
    public Result<ServiceDtos.OrderView> create(@Valid @RequestBody ServiceDtos.CreateOrderRequest req,
                                                @RequestParam(required = false) Long elderId) {
        return Result.ok(orderService.createOrder(req, elderId, CurrentUserHolder.require()));
    }

    @Operation(summary = "支付（一期模拟支付）")
    @PostMapping("/orders/{orderId}/pay")
    public Result<ServiceDtos.OrderView> pay(@PathVariable Long orderId) {
        return Result.ok(orderService.pay(orderId, CurrentUserHolder.require()));
    }

    @Operation(summary = "取消订单")
    @PostMapping("/orders/{orderId}/cancel")
    public Result<ServiceDtos.OrderView> cancel(@PathVariable Long orderId,
                                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return Result.ok(orderService.cancel(orderId, reason, CurrentUserHolder.require()));
    }

    @Operation(summary = "评价（SV-14）")
    @PostMapping("/orders/{orderId}/review")
    public Result<ServiceDtos.OrderView> review(@PathVariable Long orderId,
                                                @Valid @RequestBody ServiceDtos.ReviewRequest req) {
        return Result.ok(orderService.review(orderId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/orders/{orderId}")
    public Result<ServiceDtos.OrderView> detail(@PathVariable Long orderId) {
        return Result.ok(orderService.detail(orderId, CurrentUserHolder.require()));
    }

    @Operation(summary = "我的订单（老人/家属，SV-02）")
    @GetMapping("/my/orders")
    public Result<List<ServiceDtos.OrderView>> myOrders(@RequestParam(required = false) String status) {
        return Result.ok(orderService.listMyOrders(CurrentUserHolder.require(), status));
    }

    @Operation(summary = "机构端工单列表（SV-05）")
    @GetMapping("/provider/orders")
    public Result<List<ServiceDtos.OrderView>> providerOrders(@RequestParam(required = false) String status) {
        return Result.ok(orderService.listProviderOrders(CurrentUserHolder.require(), status));
    }

    @Operation(summary = "机构接单")
    @PostMapping("/provider/work-orders/{workOrderId}/accept")
    public Result<ServiceDtos.OrderView> accept(@PathVariable Long workOrderId,
                                                @RequestBody(required = false) ServiceDtos.AssignRequest req) {
        return Result.ok(orderService.acceptWorkOrder(workOrderId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "上门打卡（SV-06/SV-13）：action=START|END")
    @PostMapping("/provider/work-orders/{workOrderId}/checkin")
    public Result<ServiceDtos.OrderView> checkin(@PathVariable Long workOrderId,
                                                 @RequestParam String action,
                                                 @RequestBody(required = false) ServiceDtos.CheckinRequest req) {
        return Result.ok(orderService.checkin(workOrderId, action, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "订单的工单信息与操作流水")
    @GetMapping("/provider/orders/{orderId}/work-order")
    public Result<Map<String, Object>> workOrder(@PathVariable Long orderId) {
        WorkOrder wo = orderService.workOrderOf(orderId);
        List<WorkOrderLog> logs = wo == null ? List.of() : orderService.workOrderLogs(wo.getId());
        return Result.ok(Map.of("workOrder", wo == null ? Map.of() : wo, "logs", logs));
    }

    @Operation(summary = "平台订单监控（OP-04/OP-10）")
    @GetMapping("/admin/orders")
    public Result<List<ServiceDtos.OrderView>> allOrders(@RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(orderService.listAllOrders(status, Math.min(limit, 200), CurrentUserHolder.require()));
    }

    @Operation(summary = "平台人工改派（OP-04）")
    @PostMapping("/admin/orders/{orderId}/dispatch")
    public Result<ServiceDtos.OrderView> dispatch(@PathVariable Long orderId,
                                                  @Valid @RequestBody ServiceDtos.DispatchRequest req) {
        return Result.ok(orderService.manualDispatch(orderId, req.providerId(), CurrentUserHolder.require()));
    }

    @Operation(summary = "平台退款（SV-15）")
    @PostMapping("/admin/orders/{orderId}/refund")
    public Result<ServiceDtos.OrderView> refund(@PathVariable Long orderId,
                                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return Result.ok(orderService.refund(orderId, reason, CurrentUserHolder.require()));
    }
}
