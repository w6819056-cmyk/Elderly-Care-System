package com.smartelder.security;

import com.smartelder.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户。
 */
public final class CurrentUserHolder {

    private CurrentUserHolder() {
    }

    public static LoginUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser user)) {
            throw new BizException(401, "未登录");
        }
        return user;
    }
}
