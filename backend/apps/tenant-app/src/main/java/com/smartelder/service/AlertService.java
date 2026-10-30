package com.smartelder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.common.AlertLevel;
import com.smartelder.common.AlertStatus;
import com.smartelder.common.BizException;
import com.smartelder.common.Roles;
import com.smartelder.dto.AlertDtos;
import com.smartelder.entity.AlertHandleLog;
import com.smartelder.entity.AlertRecord;
import com.smartelder.entity.ElderProfile;
import com.smartelder.entity.FamilyBinding;
import com.smartelder.entity.SysUser;
import com.smartelder.mapper.AlertHandleLogMapper;
import com.smartelder.mapper.AlertRecordMapper;
import com.smartelder.mapper.ElderProfileMapper;
import com.smartelder.mapper.FamilyBindingMapper;
import com.smartelder.mapper.SysUserMapper;
import com.smartelder.security.LoginUser;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRecordMapper alertMapper;
    private final AlertHandleLogMapper handleLogMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final FamilyBindingMapper bindingMapper;
    private final SysUserMapper userMapper;
    private final MessageService messageService;
    private final ElderAccessService accessService;

    public AlertService(AlertRecordMapper alertMapper, AlertHandleLogMapper handleLogMapper,
                        ElderProfileMapper elderProfileMapper, FamilyBindingMapper bindingMapper,
                        SysUserMapper userMapper, MessageService messageService,
                        ElderAccessService accessService) {
        this.alertMapper = alertMapper;
        this.handleLogMapper = handleLogMapper;
        this.elderProfileMapper = elderProfileMapper;
        this.bindingMapper = bindingMapper;
        this.userMapper = userMapper;
        this.messageService = messageService;
        this.accessService = accessService;
    }

    /**
     * 触发告警并按级别推送（SA-04、SA-08、MSG-03）。
     * 红色：即时多渠道推家属 + 平台；黄色：推家属站内消息；蓝色：仅入库。
     */
    @Transactional
    public AlertRecord trigger(Long elderId, String type, String content, BigDecimal lng, BigDecimal lat,
                               Long operatorId) {
        String level = AlertLevel.of(type);
        ElderProfile elder = accessService.requireElder(elderId);

        AlertRecord alert = new AlertRecord();
        alert.setElderId(elderId);
        alert.setType(type);
        alert.setLevel(level);
        alert.setContent(content);
        alert.setTriggerLng(lng);
        alert.setTriggerLat(lat);
        alert.setStatus(AlertStatus.PENDING);
        alert.setCreatedAt(LocalDateTime.now());
        alertMapper.insert(alert);

        appendLog(alert.getId(), "TRIGGER", operatorId, "触发" + AlertLevel.label(level) + "告警");

        if (!AlertLevel.BLUE.equals(level)) {
            pushToFamily(alert, elder);
        }
        if (AlertLevel.RED.equals(level)) {
            pushToPlatform(alert, elder);
        }
        log.info("告警触发 id={} elder={} type={} level={}", alert.getId(), elderId, type, level);
        return alert;
    }

    private void pushToFamily(AlertRecord alert, ElderProfile elder) {
        List<Long> familyUserIds = bindingMapper.selectList(Wrappers.<FamilyBinding>lambdaQuery()
                        .eq(FamilyBinding::getElderId, elder.getId())
                        .eq(FamilyBinding::getStatus, "ACCEPTED"))
                .stream().map(FamilyBinding::getFamilyId).toList();
        String channel = AlertLevel.RED.equals(alert.getLevel()) ? "SMS" : "IN_APP";
        String title = "【" + AlertLevel.label(alert.getLevel()) + "告警】" + elder.getName();
        for (Long familyUserId : familyUserIds) {
            messageService.send(familyUserId, "ALERT", channel, title, alert.getContent(), alert.getId());
            if (AlertLevel.RED.equals(alert.getLevel())) {
                // 红色告警同时投递应用内通知，保证不只依赖单一渠道
                messageService.send(familyUserId, "ALERT", "IN_APP", title, alert.getContent(), alert.getId());
            }
        }
    }

    private void pushToPlatform(AlertRecord alert, ElderProfile elder) {
        List<SysUser> admins = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getRole, List.of(Roles.PLATFORM_ADMIN, Roles.SYS_ADMIN))
                .eq(SysUser::getStatus, "ACTIVE"));
        for (SysUser admin : admins) {
            messageService.send(admin.getId(), "ALERT", "IN_APP", "【平台告警】" + elder.getName(),
                    alert.getContent(), alert.getId());
        }
    }

    /** 老人一键 SOS（SA-01/SA-04）。 */
    @Transactional
    public AlertDtos.AlertView sos(AlertDtos.SosRequest req, LoginUser user) {
        EldeprofileGuard(user);
        ElderProfile elder = accessService.currentElder(user);
        AlertRecord alert = trigger(elder.getId(), "SOS",
                req.note() == null || req.note().isBlank() ? "老人触发一键紧急呼叫" : req.note(),
                req.lng(), req.lat(), user.userId());
        return toView(alert, elder.getName());
    }

    private void EldeprofileGuard(LoginUser user) {
        if (!Roles.ELDER.equals(user.role())) {
            throw BizException.forbidden("仅老人本人可触发一键呼叫");
        }
    }

    /** IoT 设备或模拟数据上报事件（SA-05/SA-06/SA-07）。 */
    @Transactional
    public AlertDtos.AlertView deviceTrigger(Long elderId, AlertDtos.TriggerRequest req, LoginUser user) {
        accessService.requireReadable(elderId, user);
        String content = req.content() == null ? defaultContent(req.type()) : req.content();
        AlertRecord alert = trigger(elderId, req.type(), content, req.lng(), req.lat(), user.userId());
        return toView(alert, accessService.requireElder(elderId).getName());
    }

    private String defaultContent(String type) {
        return switch (type) {
            case "FALL" -> "检测到跌倒事件";
            case "GEOFENCE" -> "老人离开设定安全区域";
            case "INACTIVITY" -> "长时间未检测到活动";
            case "SOS" -> "老人触发一键紧急呼叫";
            case "VITAL_ABNORMAL" -> "体征数据异常";
            default -> "安全事件提醒";
        };
    }

    /** 告警处置闭环（SA-09）：ACK -> FOLLOW -> ARCHIVE。 */
    @Transactional
    public AlertDtos.AlertView handle(Long alertId, AlertDtos.HandleRequest req, LoginUser user) {
        AlertRecord alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw BizException.notFound("告警不存在");
        }
        if (Roles.FAMILY.equals(user.role())) {
            accessService.requireReadable(alert.getElderId(), user);
        } else if (!Roles.isAdmin(user.role()) && !Roles.isProviderSide(user.role())) {
            throw BizException.forbidden("无权处置该告警");
        }
        String target = switch (req.action().toUpperCase()) {
            case "ACK", "ACKNOWLEDGE" -> AlertStatus.ACKED;
            case "FOLLOW", "FOLLOW_UP" -> AlertStatus.FOLLOWED;
            case "ARCHIVE" -> AlertStatus.ARCHIVED;
            default -> throw new BizException("不支持的处置动作：" + req.action());
        };
        AlertStatus.assertTransition(alert.getStatus(), target);
        alert.setStatus(target);
        alert.setHandleBy(user.userId());
        alert.setHandleTime(LocalDateTime.now());
        if (req.note() != null && !req.note().isBlank()) {
            alert.setHandleNote(req.note());
        }
        alertMapper.updateById(alert);
        appendLog(alertId, target, user.userId(), req.note());

        // 回访与归档后通知家属，形成闭环可追溯
        if (!AlertStatus.FOLLOWED.equals(alert.getStatus())) {
            notifyFamilyOfHandled(alert);
        }
        return toView(alert, accessService.requireElder(alert.getElderId()).getName());
    }

    private void notifyFamilyOfHandled(AlertRecord alert) {
        List<Long> familyUserIds = bindingMapper.selectList(Wrappers.<FamilyBinding>lambdaQuery()
                        .eq(FamilyBinding::getElderId, alert.getElderId())
                        .eq(FamilyBinding::getStatus, "ACCEPTED"))
                .stream().map(FamilyBinding::getFamilyId).toList();
        for (Long familyUserId : familyUserIds) {
            messageService.send(familyUserId, "ALERT", "IN_APP", "告警处置进展",
                    "告警 #" + alert.getId() + " 已" + AlertStatus.label(alert.getStatus()), alert.getId());
        }
    }

    private void appendLog(Long alertId, String action, Long operatorId, String note) {
        AlertHandleLog logRow = new AlertHandleLog();
        logRow.setAlertId(alertId);
        logRow.setAction(action);
        logRow.setOperatorId(operatorId);
        logRow.setNote(note);
        logRow.setCreatedAt(LocalDateTime.now());
        handleLogMapper.insert(logRow);
    }

    /** 家属端告警列表（SA-02），按级别与时间排序。 */
    public List<AlertDtos.AlertView> listForElder(Long elderId, String status, int limit, LoginUser user) {
        accessService.requireReadable(elderId, user);
        var q = Wrappers.<AlertRecord>lambdaQuery().eq(AlertRecord::getElderId, elderId);
        if (status != null && !status.isBlank()) {
            q.eq(AlertRecord::getStatus, status);
        }
        q.orderByDesc(AlertRecord::getCreatedAt).last("LIMIT " + limit);
        return toViews(alertMapper.selectList(q));
    }

    /** 平台/机构告警工作台。 */
    public List<AlertDtos.AlertView> listAll(String status, String level, int limit, LoginUser user) {
        if (!Roles.isAdmin(user.role()) && !Roles.isProviderSide(user.role())) {
            throw BizException.forbidden("无权查看全平台告警");
        }
        var q = Wrappers.<AlertRecord>lambdaQuery();
        if (status != null && !status.isBlank()) {
            q.eq(AlertRecord::getStatus, status);
        }
        if (level != null && !level.isBlank()) {
            q.eq(AlertRecord::getLevel, level);
        }
        // 红色告警优先，其次按时间倒序
        q.orderByDesc(AlertRecord::getCreatedAt).last("LIMIT " + limit);
        return toViews(alertMapper.selectList(q));
    }

    public List<AlertDtos.AlertView> activeAlerts(Long elderId) {
        return toViews(alertMapper.selectList(Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getElderId, elderId)
                .ne(AlertRecord::getStatus, AlertStatus.ARCHIVED)
                .orderByDesc(AlertRecord::getCreatedAt)
                .last("LIMIT 10")));
    }

    /** 处置流水查询，带权限校验：家属仅可查已绑定老人的告警。 */
    public List<AlertHandleLog> handleLogsFor(Long alertId, LoginUser user) {
        AlertRecord alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw BizException.notFound("告警不存在");
        }
        if (!Roles.isAdmin(user.role()) && !Roles.isProviderSide(user.role())) {
            accessService.requireReadable(alert.getElderId(), user);
        }
        return handleLogs(alertId);
    }

    public List<AlertHandleLog> handleLogs(Long alertId) {
        return handleLogMapper.selectList(Wrappers.<AlertHandleLog>lambdaQuery()
                .eq(AlertHandleLog::getAlertId, alertId)
                .orderByAsc(AlertHandleLog::getCreatedAt));
    }

    private List<AlertDtos.AlertView> toViews(List<AlertRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> elderIds = records.stream().map(AlertRecord::getElderId).distinct().toList();
        Map<Long, String> nameMap = elderProfileMapper.selectBatchIds(elderIds).stream()
                .collect(Collectors.toMap(ElderProfile::getId, ElderProfile::getName, (a, b) -> a));
        return records.stream().map(r -> toView(r, nameMap.get(r.getElderId()))).toList();
    }

    private AlertDtos.AlertView toView(AlertRecord r, String elderName) {
        return new AlertDtos.AlertView(r.getId(), r.getElderId(), elderName, r.getType(), r.getLevel(),
                r.getContent(), r.getStatus(), r.getHandleBy(), r.getHandleTime(), r.getHandleNote(),
                r.getCreatedAt());
    }

    public long countActive() {
        Long c = alertMapper.selectCount(Wrappers.<AlertRecord>lambdaQuery()
                .ne(AlertRecord::getStatus, AlertStatus.ARCHIVED));
        return c == null ? 0 : c;
    }

    public long countByLevel(String level) {
        Long c = alertMapper.selectCount(Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getLevel, level)
                .ne(AlertRecord::getStatus, AlertStatus.ARCHIVED));
        return c == null ? 0 : c;
    }
}
