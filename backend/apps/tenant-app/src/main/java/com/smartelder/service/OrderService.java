package com.smartelder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.common.BizException;
import com.smartelder.common.OrderStatus;
import com.smartelder.common.Roles;
import com.smartelder.dto.ServiceDtos;
import com.smartelder.entity.ElderProfile;
import com.smartelder.entity.ProviderOrg;
import com.smartelder.entity.ServiceItem;
import com.smartelder.entity.ServiceOrder;
import com.smartelder.entity.ServiceReview;
import com.smartelder.entity.Settlement;
import com.smartelder.entity.SysUser;
import com.smartelder.entity.WorkOrder;
import com.smartelder.entity.WorkOrderLog;
import com.smartelder.mapper.ElderProfileMapper;
import com.smartelder.mapper.ProviderOrgMapper;
import com.smartelder.mapper.ServiceItemMapper;
import com.smartelder.mapper.ServiceOrderMapper;
import com.smartelder.mapper.ServiceReviewMapper;
import com.smartelder.mapper.SettlementMapper;
import com.smartelder.mapper.SysUserMapper;
import com.smartelder.mapper.WorkOrderLogMapper;
import com.smartelder.mapper.WorkOrderMapper;
import com.smartelder.security.LoginUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ServiceItemMapper itemMapper;
    private final ServiceOrderMapper orderMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderLogMapper workOrderLogMapper;
    private final ServiceReviewMapper reviewMapper;
    private final SettlementMapper settlementMapper;
    private final ProviderOrgMapper providerMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final SysUserMapper userMapper;
    private final ElderAccessService accessService;
    private final MessageService messageService;

    public OrderService(ServiceItemMapper itemMapper, ServiceOrderMapper orderMapper,
                        WorkOrderMapper workOrderMapper, WorkOrderLogMapper workOrderLogMapper,
                        ServiceReviewMapper reviewMapper, SettlementMapper settlementMapper,
                        ProviderOrgMapper providerMapper, ElderProfileMapper elderProfileMapper,
                        SysUserMapper userMapper, ElderAccessService accessService,
                        MessageService messageService) {
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
        this.workOrderMapper = workOrderMapper;
        this.workOrderLogMapper = workOrderLogMapper;
        this.reviewMapper = reviewMapper;
        this.settlementMapper = settlementMapper;
        this.providerMapper = providerMapper;
        this.elderProfileMapper = elderProfileMapper;
        this.userMapper = userMapper;
        this.accessService = accessService;
        this.messageService = messageService;
    }

    /** 服务商城（SV-09）：只展示审核通过且在售的项目。 */
    public List<ServiceDtos.ItemView> listOnSaleItems(String category) {
        var q = Wrappers.<ServiceItem>lambdaQuery()
                .eq(ServiceItem::getStatus, "ON_SALE")
                .eq(ServiceItem::getAuditStatus, "APPROVED");
        if (category != null && !category.isBlank()) {
            q.eq(ServiceItem::getCategory, category);
        }
        q.orderByAsc(ServiceItem::getCategory).orderByAsc(ServiceItem::getPrice);
        return itemMapper.selectList(q).stream().map(this::toItemView).toList();
    }

    /**
     * 下单（SV-10）。老人为自己下单；家属代下单时 elderId 必填且需已绑定（FM-08）。
     */
    @Transactional
    public ServiceDtos.OrderView createOrder(ServiceDtos.CreateOrderRequest req, Long elderIdForFamily,
                                             LoginUser user) {
        ElderProfile elder;
        if (Roles.ELDER.equals(user.role())) {
            elder = accessService.currentElder(user);
            if (elderIdForFamily != null && !elderIdForFamily.equals(elder.getId())) {
                throw BizException.forbidden("老人只能为自己下单");
            }
        } else if (Roles.FAMILY.equals(user.role())) {
            if (elderIdForFamily == null) {
                throw new BizException("代下单需指定老人");
            }
            elder = accessService.requireReadable(elderIdForFamily, user);
        } else {
            throw BizException.forbidden("该角色无下单权限");
        }

        ServiceItem item = itemMapper.selectById(req.itemId());
        if (item == null || !"ON_SALE".equals(item.getStatus()) || !"APPROVED".equals(item.getAuditStatus())) {
            throw BizException.notFound("服务项目不存在或未上架");
        }
        if (req.appointTime() != null && req.appointTime().isBefore(LocalDateTime.now())) {
            throw new BizException("预约时间不能早于当前时间");
        }

        ServiceOrder order = new ServiceOrder();
        order.setOrderNo(generateOrderNo());
        order.setElderId(elder.getId());
        order.setItemId(item.getId());
        order.setProviderId(item.getProviderId());
        order.setCreatorId(user.userId());
        order.setAmount(item.getPrice());
        order.setStatus(OrderStatus.PENDING_PAY);
        order.setAppointTime(req.appointTime());
        order.setAddress(req.address());
        order.setRemark(req.remark());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        return toOrderView(order);
    }

    /**
     * 支付（一期为模拟支付，真实接入微信支付时替换本方法内部实现）。
     * 支付成功后进入待接单，并按派单规则生成工单（SV-11）。
     */
    @Transactional
    public ServiceDtos.OrderView pay(Long orderId, LoginUser user) {
        ServiceOrder order = requireOrder(orderId);
        requireOrderOwner(order, user);
        if (!OrderStatus.PENDING_PAY.equals(order.getStatus())) {
            throw BizException.conflict("当前状态不可支付");
        }
        OrderStatus.assertTransition(order.getStatus(), OrderStatus.PENDING_ACCEPT);
        order.setStatus(OrderStatus.PENDING_ACCEPT);
        order.setPayTime(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        dispatch(order);
        return toOrderView(order);
    }

    /**
     * 派单规则（SV-11）：
     * 1) 服务项目已归属服务商且该服务商审核通过、在服务区域内 -> 直接派给该服务商；
     * 2) 否则在服务区域内的合规服务商中，按「在办工单数最少、评分最高」选优；
     * 3) 无可用服务商 -> 置空 providerId，转平台人工派单（OP-04）。
     */
    private void dispatch(ServiceOrder order) {
        ServiceItem item = itemMapper.selectById(order.getItemId());
        String area = item == null ? null : item.getServiceArea();

        ProviderOrg chosen = null;
        if (item != null && item.getProviderId() != null) {
            ProviderOrg owner = providerMapper.selectById(item.getProviderId());
            if (owner != null && "APPROVED".equals(owner.getAuditStatus())
                    && (area == null || area.equals(owner.getServiceArea()))) {
                chosen = owner;
            }
        }

        if (chosen == null) {
            List<ProviderOrg> candidates = providerMapper.selectList(Wrappers.<ProviderOrg>lambdaQuery()
                    .eq(ProviderOrg::getAuditStatus, "APPROVED")
                    .eq(area != null, ProviderOrg::getServiceArea, area));
            chosen = candidates.stream()
                    .min(Comparator
                            .comparingLong((ProviderOrg p) -> activeWorkloadOf(p.getId()))
                            .thenComparing(Comparator.comparing(ProviderOrg::getRating).reversed()))
                    .orElse(null);
        }

        if (chosen == null) {
            order.setProviderId(null);
            order.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(order);
            log.info("订单 {} 无可用服务商，转人工派单", order.getOrderNo());
            return;
        }

        order.setProviderId(chosen.getId());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        WorkOrder workOrder = new WorkOrder();
        workOrder.setOrderId(order.getId());
        workOrder.setProviderId(chosen.getId());
        workOrder.setStatus("DISPATCHED");
        workOrder.setCreatedAt(LocalDateTime.now());
        workOrderMapper.insert(workOrder);
        appendWorkOrderLog(workOrder.getId(), "DISPATCH", 0L, "系统自动派单至 " + chosen.getName());

        notifyProvider(chosen.getId(), order, "新工单待接单");
    }

    private long activeWorkloadOf(Long providerId) {
        Long c = workOrderMapper.selectCount(Wrappers.<WorkOrder>lambdaQuery()
                .eq(WorkOrder::getProviderId, providerId)
                .in(WorkOrder::getStatus, List.of("DISPATCHED", "ACCEPTED", "IN_SERVICE")));
        return c == null ? 0 : c;
    }

    private void notifyProvider(Long providerId, ServiceOrder order, String title) {
        List<SysUser> staff = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getOrgId, providerId)
                .in(SysUser::getRole, List.of(Roles.PROVIDER, Roles.ORG_ADMIN))
                .eq(SysUser::getStatus, "ACTIVE"));
        for (SysUser u : staff) {
            messageService.send(u.getId(), "SERVICE", "IN_APP", title, "订单号 " + order.getOrderNo(), order.getId());
        }
    }

    /** 平台人工派单（OP-04 异常订单干预）。 */
    @Transactional
    public ServiceDtos.OrderView manualDispatch(Long orderId, Long providerId, LoginUser user) {
        requireAdmin(user);
        ServiceOrder order = requireOrder(orderId);
        if (!OrderStatus.PENDING_ACCEPT.equals(order.getStatus())) {
            throw BizException.conflict("仅待接单订单可改派");
        }
        ProviderOrg provider = providerMapper.selectById(providerId);
        if (provider == null || !"APPROVED".equals(provider.getAuditStatus())) {
            throw BizException.notFound("服务商不存在或未通过审核");
        }
        order.setProviderId(providerId);
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        WorkOrder wo = workOrderMapper.selectOne(Wrappers.<WorkOrder>lambdaQuery().eq(WorkOrder::getOrderId, orderId));
        if (wo == null) {
            wo = new WorkOrder();
            wo.setOrderId(orderId);
            wo.setProviderId(providerId);
            wo.setStatus("DISPATCHED");
            wo.setCreatedAt(LocalDateTime.now());
            workOrderMapper.insert(wo);
        } else {
            wo.setProviderId(providerId);
            wo.setStatus("DISPATCHED");
            wo.setNurseId(null);
            workOrderMapper.updateById(wo);
        }
        appendWorkOrderLog(wo.getId(), "REASSIGN", user.userId(), "平台改派至 " + provider.getName());
        notifyProvider(providerId, order, "工单已改派");
        return toOrderView(order);
    }

    /** 机构接单并派给护理员（SV-05）。 */
    @Transactional
    public ServiceDtos.OrderView acceptWorkOrder(Long workOrderId, ServiceDtos.AssignRequest req, LoginUser user) {
        requireProviderSide(user);
        WorkOrder wo = requireWorkOrder(workOrderId);
        requireProviderOwnership(wo, user);
        if (!"DISPATCHED".equals(wo.getStatus())) {
            throw BizException.conflict("工单当前状态不可接单");
        }
        ServiceOrder order = requireOrder(wo.getOrderId());
        OrderStatus.assertTransition(order.getStatus(), OrderStatus.IN_SERVICE);

        wo.setStatus("ACCEPTED");
        wo.setNurseId(req == null ? null : req.nurseId());
        wo.setAcceptTime(LocalDateTime.now());
        workOrderMapper.updateById(wo);
        appendWorkOrderLog(wo.getId(), "ACCEPT", user.userId(), "机构接单");

        order.setStatus(OrderStatus.IN_SERVICE);
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        return toOrderView(order);
    }

    /** 上门打卡（SV-06/SV-13）。action=START 开始服务，action=END 结束服务。 */
    @Transactional
    public ServiceDtos.OrderView checkin(Long workOrderId, String action, ServiceDtos.CheckinRequest req,
                                         LoginUser user) {
        requireProviderSide(user);
        WorkOrder wo = requireWorkOrder(workOrderId);
        requireProviderOwnership(wo, user);
        LocalDateTime now = LocalDateTime.now();
        if ("START".equalsIgnoreCase(action)) {
            if (!"ACCEPTED".equals(wo.getStatus())) {
                throw BizException.conflict("工单未接单，无法开始服务");
            }
            wo.setStatus("IN_SERVICE");
            wo.setStartTime(now);
            wo.setStartLng(req == null ? null : req.lng());
            wo.setStartLat(req == null ? null : req.lat());
            appendWorkOrderLog(wo.getId(), "CHECKIN_START", user.userId(), "服务开始打卡");
        } else if ("END".equalsIgnoreCase(action)) {
            if (!"IN_SERVICE".equals(wo.getStatus())) {
                throw BizException.conflict("工单未处于服务中，无法结束服务");
            }
            wo.setStatus("COMPLETED");
            wo.setEndTime(now);
            wo.setEndLng(req == null ? null : req.lng());
            wo.setEndLat(req == null ? null : req.lat());
            wo.setServiceNote(req == null ? null : req.note());
            workOrderMapper.updateById(wo);
            appendWorkOrderLog(wo.getId(), "CHECKIN_END", user.userId(), "服务结束打卡");

            ServiceOrder order = requireOrder(wo.getOrderId());
            OrderStatus.assertTransition(order.getStatus(), OrderStatus.PENDING_REVIEW);
            order.setStatus(OrderStatus.PENDING_REVIEW);
            order.setUpdatedAt(now);
            orderMapper.updateById(order);
            createSettlement(order);
            return toOrderView(order);
        } else {
            throw new BizException("action 仅支持 START 或 END");
        }
        workOrderMapper.updateById(wo);
        return toOrderView(requireOrder(wo.getOrderId()));
    }

    /** 服务完成后生成待结算流水（SV-08）。 */
    private void createSettlement(ServiceOrder order) {
        if (order.getProviderId() == null) {
            return;
        }
        Settlement s = new Settlement();
        s.setProviderId(order.getProviderId());
        s.setOrderId(order.getId());
        s.setAmount(order.getAmount());
        s.setStatus("PENDING");
        s.setCreatedAt(LocalDateTime.now());
        settlementMapper.insert(s);
    }

    /** 评价（SV-14），完成评价后订单结束。 */
    @Transactional
    public ServiceDtos.OrderView review(Long orderId, ServiceDtos.ReviewRequest req, LoginUser user) {
        ServiceOrder order = requireOrder(orderId);
        if (Roles.ELDER.equals(user.role())) {
            ElderProfile self = accessService.currentElder(user);
            if (!self.getId().equals(order.getElderId())) {
                throw BizException.forbidden("只能评价自己的订单");
            }
        } else if (Roles.FAMILY.equals(user.role())) {
            accessService.requireReadable(order.getElderId(), user);
        } else {
            throw BizException.forbidden("该角色无评价权限");
        }
        if (!OrderStatus.PENDING_REVIEW.equals(order.getStatus())) {
            throw BizException.conflict("当前状态不可评价");
        }
        if (req.score() < 1 || req.score() > 5) {
            throw new BizException("评分需在 1-5 之间");
        }
        Long exists = reviewMapper.selectCount(Wrappers.<ServiceReview>lambdaQuery()
                .eq(ServiceReview::getOrderId, orderId));
        if (exists != null && exists > 0) {
            throw BizException.conflict("该订单已评价");
        }
        ServiceReview review = new ServiceReview();
        review.setOrderId(orderId);
        review.setElderId(order.getElderId());
        review.setReviewerId(user.userId());
        review.setScore(req.score());
        review.setContent(req.content());
        review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);

        OrderStatus.assertTransition(order.getStatus(), OrderStatus.COMPLETED);
        order.setStatus(OrderStatus.COMPLETED);
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        return toOrderView(order);
    }

    /** 取消（SV-12）：待支付/待接单可取消；已支付则标记退款。 */
    @Transactional
    public ServiceDtos.OrderView cancel(Long orderId, String reason, LoginUser user) {
        ServiceOrder order = requireOrder(orderId);
        requireOrderOwner(order, user);
        if (!OrderStatus.canCancel(order.getStatus())) {
            throw BizException.conflict("当前状态不可取消，请走退款流程");
        }
        String target = OrderStatus.PENDING_PAY.equals(order.getStatus())
                ? OrderStatus.CANCELLED : OrderStatus.REFUNDED;
        OrderStatus.assertTransition(order.getStatus(), target);
        order.setStatus(target);
        order.setCancelReason(reason);
        if (OrderStatus.REFUNDED.equals(target)) {
            order.setRefundAmount(order.getAmount());
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        WorkOrder wo = workOrderMapper.selectOne(Wrappers.<WorkOrder>lambdaQuery().eq(WorkOrder::getOrderId, orderId));
        if (wo != null) {
            wo.setStatus("CANCELLED");
            workOrderMapper.updateById(wo);
            appendWorkOrderLog(wo.getId(), "CANCEL", user.userId(), "订单" + OrderStatus.label(target));
        }
        return toOrderView(order);
    }

    /** 退款（SV-15），一期为全额退款占位实现。 */
    @Transactional
    public ServiceDtos.OrderView refund(Long orderId, String reason, LoginUser user) {
        requireAdmin(user);
        ServiceOrder order = requireOrder(orderId);
        if (!List.of(OrderStatus.PENDING_ACCEPT, OrderStatus.IN_SERVICE).contains(order.getStatus())) {
            throw BizException.conflict("当前状态不可退款");
        }
        OrderStatus.assertTransition(order.getStatus(), OrderStatus.REFUNDED);
        order.setStatus(OrderStatus.REFUNDED);
        order.setRefundAmount(order.getAmount());
        order.setCancelReason(reason);
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        WorkOrder wo = workOrderMapper.selectOne(Wrappers.<WorkOrder>lambdaQuery().eq(WorkOrder::getOrderId, orderId));
        if (wo != null) {
            wo.setStatus("CANCELLED");
            workOrderMapper.updateById(wo);
        }
        settlementMapper.delete(Wrappers.<Settlement>lambdaQuery().eq(Settlement::getOrderId, orderId));
        return toOrderView(order);
    }

    public List<ServiceDtos.OrderView> listMyOrders(LoginUser user, String status) {
        var q = Wrappers.<ServiceOrder>lambdaQuery();
        if (Roles.ELDER.equals(user.role())) {
            ElderProfile self = accessService.currentElder(user);
            q.eq(ServiceOrder::getElderId, self.getId());
        } else if (Roles.FAMILY.equals(user.role())) {
            List<Long> elderIds = accessService.boundElders(user.userId()).stream()
                    .map(ElderProfile::getId).toList();
            if (elderIds.isEmpty()) {
                return List.of();
            }
            q.in(ServiceOrder::getElderId, elderIds);
        } else {
            throw BizException.forbidden("该角色无此视图");
        }
        if (status != null && !status.isBlank()) {
            q.eq(ServiceOrder::getStatus, status);
        }
        q.orderByDesc(ServiceOrder::getCreatedAt).last("LIMIT 100");
        return orderMapper.selectList(q).stream().map(this::toOrderView).toList();
    }

    /** 机构端工单列表。 */
    public List<ServiceDtos.OrderView> listProviderOrders(LoginUser user, String status) {
        requireProviderSide(user);
        Long orgId = user.orgId();
        if (orgId == null) {
            throw BizException.forbidden("账号未绑定机构");
        }
        var q = Wrappers.<ServiceOrder>lambdaQuery().eq(ServiceOrder::getProviderId, orgId);
        if (status != null && !status.isBlank()) {
            q.eq(ServiceOrder::getStatus, status);
        }
        q.orderByDesc(ServiceOrder::getCreatedAt).last("LIMIT 100");
        return orderMapper.selectList(q).stream().map(this::toOrderView).toList();
    }

    /** 平台订单监控（OP-04/OP-10）。 */
    public List<ServiceDtos.OrderView> listAllOrders(String status, int limit, LoginUser user) {
        requireAdmin(user);
        var q = Wrappers.<ServiceOrder>lambdaQuery();
        if (status != null && !status.isBlank()) {
            q.eq(ServiceOrder::getStatus, status);
        }
        q.orderByDesc(ServiceOrder::getCreatedAt).last("LIMIT " + limit);
        return orderMapper.selectList(q).stream().map(this::toOrderView).toList();
    }

    /** 家属面板用：某老人进行中的订单。 */
    public List<ServiceDtos.OrderView> activeOrdersOf(Long elderId) {
        return orderMapper.selectList(Wrappers.<ServiceOrder>lambdaQuery()
                        .eq(ServiceOrder::getElderId, elderId)
                        .in(ServiceOrder::getStatus, List.of(OrderStatus.PENDING_PAY, OrderStatus.PENDING_ACCEPT,
                                OrderStatus.IN_SERVICE, OrderStatus.PENDING_REVIEW))
                        .orderByDesc(ServiceOrder::getCreatedAt)
                        .last("LIMIT 10"))
                .stream().map(this::toOrderView).toList();
    }

    public ServiceDtos.OrderView detail(Long orderId, LoginUser user) {
        ServiceOrder order = requireOrder(orderId);
        if (Roles.isAdmin(user.role())) {
            return toOrderView(order);
        }
        if (Roles.isProviderSide(user.role())) {
            if (Objects.equals(order.getProviderId(), user.orgId())) {
                return toOrderView(order);
            }
            throw BizException.forbidden("无权查看该订单");
        }
        requireOrderOwner(order, user);
        return toOrderView(order);
    }

    public WorkOrder workOrderOf(Long orderId) {
        return workOrderMapper.selectOne(Wrappers.<WorkOrder>lambdaQuery().eq(WorkOrder::getOrderId, orderId));
    }

    public List<WorkOrderLog> workOrderLogs(Long workOrderId) {
        return workOrderLogMapper.selectList(Wrappers.<WorkOrderLog>lambdaQuery()
                .eq(WorkOrderLog::getWorkOrderId, workOrderId)
                .orderByAsc(WorkOrderLog::getCreatedAt));
    }

    private void appendWorkOrderLog(Long workOrderId, String action, Long operatorId, String note) {
        WorkOrderLog wl = new WorkOrderLog();
        wl.setWorkOrderId(workOrderId);
        wl.setAction(action);
        wl.setOperatorId(operatorId);
        wl.setNote(note);
        wl.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(wl);
    }

    private void requireOrderOwner(ServiceOrder order, LoginUser user) {
        if (Roles.ELDER.equals(user.role())) {
            ElderProfile self = accessService.currentElder(user);
            if (!self.getId().equals(order.getElderId())) {
                throw BizException.forbidden("无权操作该订单");
            }
        } else if (Roles.FAMILY.equals(user.role())) {
            accessService.requireReadable(order.getElderId(), user);
        } else {
            throw BizException.forbidden("无权操作该订单");
        }
    }

    private void requireProviderSide(LoginUser user) {
        if (!Roles.isProviderSide(user.role())) {
            throw BizException.forbidden("该操作仅限服务方");
        }
    }

    private void requireProviderOwnership(WorkOrder wo, LoginUser user) {
        if (!Objects.equals(wo.getProviderId(), user.orgId())) {
            throw BizException.forbidden("工单不属于本机构");
        }
    }

    private void requireAdmin(LoginUser user) {
        if (!Roles.isAdmin(user.role())) {
            throw BizException.forbidden("该操作仅限平台管理员");
        }
    }

    private ServiceOrder requireOrder(Long orderId) {
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单不存在");
        }
        return order;
    }

    private WorkOrder requireWorkOrder(Long workOrderId) {
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        if (wo == null) {
            throw BizException.notFound("工单不存在");
        }
        return wo;
    }

    private String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private ServiceDtos.ItemView toItemView(ServiceItem item) {
        return new ServiceDtos.ItemView(item.getId(), item.getName(), item.getCategory(), item.getPrice(),
                item.getDurationMin(), item.getServiceArea(), item.getDescription(), item.getProviderId());
    }

    private ServiceDtos.OrderView toOrderView(ServiceOrder o) {
        ServiceItem item = itemMapper.selectById(o.getItemId());
        ElderProfile elder = elderProfileMapper.selectById(o.getElderId());
        ProviderOrg provider = o.getProviderId() == null ? null : providerMapper.selectById(o.getProviderId());
        WorkOrder wo = workOrderMapper.selectOne(Wrappers.<WorkOrder>lambdaQuery().eq(WorkOrder::getOrderId, o.getId()));
        return new ServiceDtos.OrderView(o.getId(), o.getOrderNo(), o.getElderId(),
                elder == null ? null : elder.getName(), o.getItemId(), item == null ? null : item.getName(),
                o.getProviderId(), provider == null ? null : provider.getName(), o.getAmount(), o.getStatus(),
                o.getAppointTime(), o.getAddress(), o.getRemark(), o.getCreatedAt(),
                wo == null ? null : wo.getStatus(), wo == null ? null : wo.getNurseId());
    }
}
