package com.smartelder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.common.BizException;
import com.smartelder.common.OrderStatus;
import com.smartelder.common.Roles;
import com.smartelder.dto.AdminDtos;
import com.smartelder.entity.ProviderOrg;
import com.smartelder.entity.ServiceItem;
import com.smartelder.entity.ServiceOrder;
import com.smartelder.entity.SysUser;
import com.smartelder.mapper.ProviderOrgMapper;
import com.smartelder.mapper.ServiceItemMapper;
import com.smartelder.mapper.ServiceOrderMapper;
import com.smartelder.mapper.SysUserMapper;
import com.smartelder.security.LoginUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private final SysUserMapper userMapper;
    private final ProviderOrgMapper providerMapper;
    private final ServiceItemMapper itemMapper;
    private final ServiceOrderMapper orderMapper;
    private final AlertService alertService;
    private final PasswordEncoder passwordEncoder;

    public AdminService(SysUserMapper userMapper, ProviderOrgMapper providerMapper,
                        ServiceItemMapper itemMapper, ServiceOrderMapper orderMapper, AlertService alertService,
                        PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.providerMapper = providerMapper;
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
        this.alertService = alertService;
        this.passwordEncoder = passwordEncoder;
    }

    private void requireAdmin(LoginUser user) {
        if (!Roles.isAdmin(user.role())) {
            throw BizException.forbidden("该操作仅限平台管理员");
        }
    }

    public List<AdminDtos.ProviderRow> listProviders(String auditStatus, LoginUser user) {
        requireAdmin(user);
        var q = Wrappers.<ProviderOrg>lambdaQuery();
        if (auditStatus != null && !auditStatus.isBlank()) {
            q.eq(ProviderOrg::getAuditStatus, auditStatus);
        }
        q.orderByDesc(ProviderOrg::getCreatedAt);
        return providerMapper.selectList(q).stream()
                .map(p -> new AdminDtos.ProviderRow(p.getId(), p.getName(), p.getContactName(), p.getContactPhone(),
                        p.getServiceArea(), p.getRating(), p.getAuditStatus(), p.getAuditNote(), p.getCreatedAt()))
                .toList();
    }

    /** OP-02/OP-08 服务商入驻审核。 */
    @Transactional
    public AdminDtos.ProviderRow auditProvider(Long providerId, AdminDtos.AuditRequest req, LoginUser user) {
        requireAdmin(user);
        ProviderOrg org = providerMapper.selectById(providerId);
        if (org == null) {
            throw BizException.notFound("服务商不存在");
        }
        if (!"PENDING".equals(org.getAuditStatus())) {
            throw BizException.conflict("该申请已审核");
        }
        String decision = parseDecision(req.decision());
        org.setAuditStatus(decision);
        org.setAuditNote(req.note());
        providerMapper.updateById(org);

        // 审核通过后开通该机构的管理员账号（手机号即联系人手机号，初始密码通过消息下发）
        if ("APPROVED".equals(decision)) {
            openOrgAdminAccount(org);
        }
        return new AdminDtos.ProviderRow(org.getId(), org.getName(), org.getContactName(), org.getContactPhone(),
                org.getServiceArea(), org.getRating(), org.getAuditStatus(), org.getAuditNote(), org.getCreatedAt());
    }

    private void openOrgAdminAccount(ProviderOrg org) {
        SysUser existing = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, org.getContactPhone()));
        if (existing != null) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setPhone(org.getContactPhone());
        // 初始密码 888888，需在首次登录后修改；生产环境应生成随机密码并通过短信下发
        admin.setPasswordHash(passwordEncoder.encode("888888"));
        admin.setNickname(org.getName() + "管理员");
        admin.setRole(Roles.ORG_ADMIN);
        admin.setOrgId(org.getId());
        admin.setStatus("ACTIVE");
        admin.setCreatedAt(LocalDateTime.now());
        userMapper.insert(admin);
    }

    public List<AdminDtos.PendingItemRow> listItems(String auditStatus, LoginUser user) {
        requireAdmin(user);
        LambdaQueryWrapper<ServiceItem> q = Wrappers.<ServiceItem>lambdaQuery();
        if (auditStatus != null && !auditStatus.isBlank()) {
            q.eq(ServiceItem::getAuditStatus, auditStatus);
        }
        q.orderByDesc(ServiceItem::getCreatedAt);
        List<ServiceItem> items = itemMapper.selectList(q);
        Map<Long, String> orgNames = providerMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProviderOrg::getId, ProviderOrg::getName, (a, b) -> a));
        return items.stream()
                .map(i -> new AdminDtos.PendingItemRow(i.getId(), i.getName(), i.getCategory(), i.getPrice(),
                        i.getServiceArea(), i.getProviderId(),
                        i.getProviderId() == null ? null : orgNames.get(i.getProviderId()),
                        i.getAuditStatus(), i.getStatus()))
                .toList();
    }

    /** OP-03/OP-09 服务项目审核。未审核通过的项目商城不可见。 */
    @Transactional
    public AdminDtos.PendingItemRow auditItem(Long itemId, AdminDtos.AuditRequest req, LoginUser user) {
        requireAdmin(user);
        ServiceItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("服务项目不存在");
        }
        if (!"PENDING".equals(item.getAuditStatus())) {
            throw BizException.conflict("该项目已审核");
        }
        String decision = parseDecision(req.decision());
        item.setAuditStatus(decision);
        if ("REJECTED".equals(decision)) {
            item.setStatus("OFF_SALE");
        }
        itemMapper.updateById(item);
        return new AdminDtos.PendingItemRow(item.getId(), item.getName(), item.getCategory(), item.getPrice(),
                item.getServiceArea(), item.getProviderId(), null, item.getAuditStatus(), item.getStatus());
    }

    private String parseDecision(String decision) {
        return switch (decision.toUpperCase()) {
            case "APPROVE", "APPROVED", "PASS" -> "APPROVED";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> throw new BizException("审核结论仅支持 APPROVE 或 REJECT");
        };
    }

    /** OP-06/OP-12 数据大屏与统计报表。 */
    public AdminDtos.DashboardStats dashboard(LoginUser user) {
        if (!Roles.isAdmin(user.role()) && !Roles.isProviderSide(user.role())) {
            throw BizException.forbidden("无权查看运营数据");
        }
        long elders = count(userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole, Roles.ELDER)));
        long families = count(userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole, Roles.FAMILY)));
        long providers = count(providerMapper.selectCount(
                Wrappers.<ProviderOrg>lambdaQuery().eq(ProviderOrg::getAuditStatus, "APPROVED")));
        long orders = count(orderMapper.selectCount(null));
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayOrders = count(orderMapper.selectCount(
                Wrappers.<ServiceOrder>lambdaQuery().ge(ServiceOrder::getCreatedAt, todayStart)));
        long pendingAudit = count(providerMapper.selectCount(
                Wrappers.<ProviderOrg>lambdaQuery().eq(ProviderOrg::getAuditStatus, "PENDING")))
                + count(itemMapper.selectCount(
                Wrappers.<ServiceItem>lambdaQuery().eq(ServiceItem::getAuditStatus, "PENDING")));

        List<ServiceOrder> paid = orderMapper.selectList(Wrappers.<ServiceOrder>lambdaQuery()
                .in(ServiceOrder::getStatus, List.of(OrderStatus.PENDING_REVIEW, OrderStatus.COMPLETED))
                .isNotNull(ServiceOrder::getPayTime));
        BigDecimal revenue = paid.stream()
                .map(ServiceOrder::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AdminDtos.DashboardStats(elders, families, providers, orders, todayOrders,
                alertService.countActive(), alertService.countByLevel("RED"), revenue, pendingAudit);
    }

    private long count(Long v) {
        return v == null ? 0 : v;
    }

    /** OP-01 用户管理：查询与停用。 */
    public List<SysUser> listUsers(String role, String keyword, LoginUser user) {
        requireAdmin(user);
        var q = Wrappers.<SysUser>lambdaQuery();
        if (role != null && !role.isBlank()) {
            q.eq(SysUser::getRole, role);
        }
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(SysUser::getPhone, keyword).or().like(SysUser::getNickname, keyword));
        }
        q.orderByDesc(SysUser::getCreatedAt).last("LIMIT 200");
        List<SysUser> users = userMapper.selectList(q);
        users.forEach(u -> u.setPasswordHash(null));
        return users;
    }

    @Transactional
    public void changeUserStatus(Long userId, String status, LoginUser user) {
        requireAdmin(user);
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new BizException("状态仅支持 ACTIVE 或 DISABLED");
        }
        SysUser target = userMapper.selectById(userId);
        if (target == null) {
            throw BizException.notFound("用户不存在");
        }
        if (Roles.SYS_ADMIN.equals(target.getRole())) {
            throw BizException.forbidden("系统管理员账号不可停用");
        }
        target.setStatus(status);
        userMapper.updateById(target);
    }
}
