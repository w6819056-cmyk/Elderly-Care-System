package com.smartelder.common;

public enum ErrorCode {
    OK("OK", "成功", 200),
    INVALID_REQUEST("INVALID_REQUEST", "请求参数错误", 400),
    UNAUTHORIZED("UNAUTHORIZED", "未登录或登录已失效", 401),
    TOKEN_INVALID("TOKEN_INVALID", "登录凭证无效", 401),
    FORBIDDEN("FORBIDDEN", "无权执行该操作", 403),
    PERMISSION_DENIED("PERMISSION_DENIED", "无权限执行该操作", 403),
    NOT_FOUND("NOT_FOUND", "资源不存在", 404),
    CONFLICT("CONFLICT", "数据已被其他操作修改", 409),
    BUSINESS_RULE_VIOLATION("BUSINESS_RULE_VIOLATION", "业务规则校验失败", 422),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁", 429),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误", 500),
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", "依赖服务不可用", 503),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "幂等键已被其他请求使用", 409),
    MODULE_NOT_AUTHORIZED("MODULE_NOT_AUTHORIZED", "企业未获得该模块授权", 403),
    MODULE_NOT_ASSIGNED("MODULE_NOT_ASSIGNED", "该门店未开通该模块", 403),
    MODULE_DEPENDENCY_MISSING("MODULE_DEPENDENCY_MISSING", "缺少正式授权的依赖模块", 422);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
