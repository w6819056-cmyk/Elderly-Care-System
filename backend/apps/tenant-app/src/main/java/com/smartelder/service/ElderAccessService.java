package com.smartelder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.common.BizException;
import com.smartelder.common.Roles;
import com.smartelder.entity.ElderProfile;
import com.smartelder.entity.FamilyBinding;
import com.smartelder.mapper.ElderProfileMapper;
import com.smartelder.mapper.FamilyBindingMapper;
import com.smartelder.security.LoginUser;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 老人数据访问授权：老人看自己，家属看已绑定的老人，管理员与已接单服务方按业务需要查看。
 */
@Service
public class ElderAccessService {

    private final ElderProfileMapper elderProfileMapper;
    private final FamilyBindingMapper bindingMapper;

    public ElderAccessService(ElderProfileMapper elderProfileMapper, FamilyBindingMapper bindingMapper) {
        this.elderProfileMapper = elderProfileMapper;
        this.bindingMapper = bindingMapper;
    }

    public ElderProfile requireElder(Long elderId) {
        ElderProfile profile = elderProfileMapper.selectById(elderId);
        if (profile == null) {
            throw BizException.notFound("老人档案不存在");
        }
        return profile;
    }

    public ElderProfile currentElder(LoginUser user) {
        if (!Roles.ELDER.equals(user.role())) {
            throw BizException.forbidden("该操作仅限老人本人");
        }
        ElderProfile profile = elderProfileMapper.selectOne(
                Wrappers.<ElderProfile>lambdaQuery().eq(ElderProfile::getUserId, user.userId()));
        if (profile == null) {
            throw BizException.notFound("老人档案不存在");
        }
        return profile;
    }

    /** 老人本人、已绑定家属、平台管理员可访问；其他角色拒绝。 */
    public ElderProfile requireReadable(Long elderId, LoginUser user) {
        ElderProfile profile = requireElder(elderId);
        if (Roles.ELDER.equals(user.role()) && profile.getUserId().equals(user.userId())) {
            return profile;
        }
        if (Roles.isAdmin(user.role())) {
            return profile;
        }
        if (Roles.FAMILY.equals(user.role()) && isBound(elderId, user.userId())) {
            return profile;
        }
        throw BizException.forbidden("无权查看该老人数据");
    }

    public boolean isBound(Long elderId, Long familyUserId) {
        Long cnt = bindingMapper.selectCount(Wrappers.<FamilyBinding>lambdaQuery()
                .eq(FamilyBinding::getElderId, elderId)
                .eq(FamilyBinding::getFamilyId, familyUserId)
                .eq(FamilyBinding::getStatus, "ACCEPTED"));
        return cnt != null && cnt > 0;
    }

    /**
     * 家属发起绑定，需求 USR-02 要求双向确认，因此初始状态为 PENDING，需老人确认。
     */
    public FamilyBinding requestBinding(Long elderId, Long familyUserId, String relation) {
        requireElder(elderId);
        Long exists = bindingMapper.selectCount(Wrappers.<FamilyBinding>lambdaQuery()
                .eq(FamilyBinding::getElderId, elderId)
                .eq(FamilyBinding::getFamilyId, familyUserId));
        if (exists != null && exists > 0) {
            throw BizException.conflict("已存在绑定记录");
        }
        FamilyBinding binding = new FamilyBinding();
        binding.setElderId(elderId);
        binding.setFamilyId(familyUserId);
        binding.setRelation(relation == null ? "家属" : relation);
        binding.setStatus("PENDING");
        binding.setCreatedAt(LocalDateTime.now());
        bindingMapper.insert(binding);
        return binding;
    }

    public void confirmBinding(Long bindingId, LoginUser user, boolean accept) {
        FamilyBinding binding = bindingMapper.selectById(bindingId);
        if (binding == null) {
            throw BizException.notFound("绑定申请不存在");
        }
        ElderProfile profile = requireElder(binding.getElderId());
        if (!profile.getUserId().equals(user.userId())) {
            throw BizException.forbidden("仅老人本人可确认绑定");
        }
        if (!"PENDING".equals(binding.getStatus())) {
            throw BizException.conflict("该申请已处理");
        }
        binding.setStatus(accept ? "ACCEPTED" : "REJECTED");
        bindingMapper.updateById(binding);
    }

    public List<FamilyBinding> bindingsOf(Long elderId) {
        return bindingMapper.selectList(Wrappers.<FamilyBinding>lambdaQuery()
                .eq(FamilyBinding::getElderId, elderId));
    }

    /** 家属已绑定的老人列表。 */
    public List<ElderProfile> boundElders(Long familyUserId) {
        List<Long> elderIds = bindingMapper.selectList(Wrappers.<FamilyBinding>lambdaQuery()
                        .eq(FamilyBinding::getFamilyId, familyUserId)
                        .eq(FamilyBinding::getStatus, "ACCEPTED"))
                .stream().map(FamilyBinding::getElderId).toList();
        if (elderIds.isEmpty()) {
            return List.of();
        }
        return elderProfileMapper.selectBatchIds(elderIds);
    }
}
