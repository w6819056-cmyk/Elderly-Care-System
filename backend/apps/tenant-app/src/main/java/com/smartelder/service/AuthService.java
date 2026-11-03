package com.smartelder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartelder.common.BizException;
import com.smartelder.common.Roles;
import com.smartelder.dto.AuthDtos;
import com.smartelder.entity.ElderProfile;
import com.smartelder.entity.SysUser;
import com.smartelder.mapper.ElderProfileMapper;
import com.smartelder.mapper.SysUserMapper;
import com.smartelder.security.JwtService;
import java.time.LocalDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(SysUserMapper userMapper, ElderProfileMapper elderProfileMapper,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.elderProfileMapper = elderProfileMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest req) {
        if (!Roles.ELDER.equals(req.role()) && !Roles.FAMILY.equals(req.role())) {
            throw new BizException("该接口仅支持注册老人或家属账号");
        }
        Long exists = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, req.phone()));
        if (exists != null && exists > 0) {
            throw BizException.conflict("手机号已注册");
        }
        SysUser user = new SysUser();
        user.setPhone(req.phone());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname());
        user.setRole(req.role());
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        if (Roles.ELDER.equals(req.role())) {
            ElderProfile profile = new ElderProfile();
            profile.setUserId(user.getId());
            profile.setName(req.nickname());
            profile.setGender("UNKNOWN");
            profile.setCreatedAt(LocalDateTime.now());
            elderProfileMapper.insert(profile);
        }
        return buildToken(user);
    }

    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest req) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, req.phone()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BizException(401, "手机号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw BizException.forbidden("账号已被停用，请联系平台");
        }
        return buildToken(user);
    }

    public AuthDtos.UserInfo me(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return toInfo(user);
    }

    private AuthDtos.TokenResponse buildToken(SysUser user) {
        String token = jwtService.issue(user.getId(), user.getRole(), user.getOrgId());
        return new AuthDtos.TokenResponse(token, toInfo(user));
    }

    private AuthDtos.UserInfo toInfo(SysUser user) {
        Long elderId = null;
        if (Roles.ELDER.equals(user.getRole())) {
            ElderProfile profile = elderProfileMapper.selectOne(
                    Wrappers.<ElderProfile>lambdaQuery().eq(ElderProfile::getUserId, user.getId()));
            elderId = profile == null ? null : profile.getId();
        }
        return new AuthDtos.UserInfo(user.getId(), user.getPhone(), user.getNickname(),
                user.getRole(), user.getOrgId(), elderId);
    }
}
