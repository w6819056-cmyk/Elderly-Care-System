package com.smartelder.service;

import com.smartelder.common.BizException;
import com.smartelder.common.Roles;
import com.smartelder.dto.HealthDtos;
import com.smartelder.entity.ElderProfile;
import com.smartelder.entity.FamilyBinding;
import com.smartelder.security.LoginUser;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 家人互联（需求 3.9）：状态面板、绑定关系、告警联动查看。
 */
@Service
public class FamilyService {

    private final ElderAccessService accessService;
    private final HealthService healthService;
    private final AlertService alertService;
    private final OrderService orderService;

    public FamilyService(ElderAccessService accessService, HealthService healthService,
                         AlertService alertService, OrderService orderService) {
        this.accessService = accessService;
        this.healthService = healthService;
        this.alertService = alertService;
        this.orderService = orderService;
    }

    /** FM-01/FM-05 老人状态面板：基础信息 + 最新体征 + 未归档告警 + 在办服务。 */
    public HealthDtos.ElderSnapshot snapshot(Long elderId, LoginUser user) {
        ElderProfile elder = accessService.requireReadable(elderId, user);
        return new HealthDtos.ElderSnapshot(
                elder.getId(), elder.getName(), elder.getAddress(),
                elder.getEmergencyContact(), elder.getEmergencyPhone(),
                healthService.latestVitals(elderId),
                alertService.activeAlerts(elderId),
                orderService.activeOrdersOf(elderId),
                healthService.listMedications(elderId, user));
    }

    public List<ElderProfile> myElders(LoginUser user) {
        if (!Roles.FAMILY.equals(user.role())) {
            throw BizException.forbidden("该视图仅限家属");
        }
        return accessService.boundElders(user.userId());
    }

    public FamilyBinding bind(Long elderId, String relation, LoginUser user) {
        if (!Roles.FAMILY.equals(user.role())) {
            throw BizException.forbidden("仅家属账号可发起绑定");
        }
        return accessService.requestBinding(elderId, user.userId(), relation);
    }

    public void decide(Long bindingId, boolean accept, LoginUser user) {
        accessService.confirmBinding(bindingId, user, accept);
    }

    public List<FamilyBinding> pendingOf(LoginUser user) {
        ElderProfile self = accessService.currentElder(user);
        return accessService.bindingsOf(self.getId());
    }
}
