package com.smartelder.security;

/**
 * 认证后放入 SecurityContext 的当前用户。
 */
public record LoginUser(Long userId, String role, Long orgId) {
}
