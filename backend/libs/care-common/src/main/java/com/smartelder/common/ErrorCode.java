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
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", "请求体过大", 413),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误", 500),
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", "依赖服务不可用", 503),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "幂等键已被其他请求使用", 409),
    MODULE_NOT_AUTHORIZED("MODULE_NOT_AUTHORIZED", "企业未获得该模块授权", 403),
    MODULE_NOT_ASSIGNED("MODULE_NOT_ASSIGNED", "该门店未开通该模块", 403),
    MODULE_DEPENDENCY_MISSING("MODULE_DEPENDENCY_MISSING", "缺少正式授权的依赖模块", 422),
    ACTIVATION_CODE_INVALID("ACTIVATION_CODE_INVALID", "激活码无效、已过期或尝试次数已用尽", 400),
    ACTIVATION_CODE_USED("ACTIVATION_CODE_USED", "激活码已被使用", 409),
    INSTANCE_SIGNATURE_INVALID("INSTANCE_SIGNATURE_INVALID", "实例签名无效", 403),
    INSTANCE_NOT_FOUND("INSTANCE_NOT_FOUND", "部署实例不存在", 404),
    INSTANCE_IDENTITY_CONFLICT("INSTANCE_IDENTITY_CONFLICT", "部署实例身份已存在", 409),
    ENTERPRISE_STATE_CONFLICT("ENTERPRISE_STATE_CONFLICT", "企业状态流转不合法", 409),
    ENTERPRISE_SUSPENDED("ENTERPRISE_SUSPENDED", "企业已暂停或关闭", 409),
    LICENSE_NOT_FOUND("LICENSE_NOT_FOUND", "授权文件不存在", 404),
    LICENSE_SIGNATURE_INVALID("LICENSE_SIGNATURE_INVALID", "授权签名无效", 403),
    MODULE_NOT_FOUND("MODULE_NOT_FOUND", "模块不存在", 404),
    MODULE_CODE_CONFLICT("MODULE_CODE_CONFLICT", "模块编码已存在", 409),
    MENU_ORDER_CONFLICT("MENU_ORDER_CONFLICT", "菜单顺序已被其他在售模块占用", 409),
    MODULE_STATE_CONFLICT("MODULE_STATE_CONFLICT", "模块状态流转不合法", 409),
    MODULE_VERSION_CONFLICT("MODULE_VERSION_CONFLICT", "模块版本已存在", 409),
    MODULE_DEPENDENCY_INVALID("MODULE_DEPENDENCY_INVALID", "模块依赖配置无效", 422),
    MODULE_DEPENDENCY_CYCLE("MODULE_DEPENDENCY_CYCLE", "模块依赖存在循环", 422),
    MODULE_PRICE_INVALID("MODULE_PRICE_INVALID", "模块价格配置无效", 422),
    MODULE_VERSION_INVALID("MODULE_VERSION_INVALID", "模块版本格式无效", 422),
    MODULE_NOT_ON_SALE("MODULE_NOT_ON_SALE", "模块未在售", 422),
    MODULE_PRICE_NOT_FOUND("MODULE_PRICE_NOT_FOUND", "模块价格方案不存在", 404),
    MODULE_ALREADY_PERPETUAL("MODULE_ALREADY_PERPETUAL", "模块已经永久授权，无需再次购买", 422),
    PLAN_NOT_FOUND("PLAN_NOT_FOUND", "套餐不存在", 404),
    PLAN_CODE_CONFLICT("PLAN_CODE_CONFLICT", "套餐编码已存在", 409),
    PLAN_INVALID("PLAN_INVALID", "套餐配置无效", 422),
    PLAN_ALREADY_OWNED("PLAN_ALREADY_OWNED", "套餐内模块均已获得正式授权", 422),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "销售订单不存在", 404),
    ORDER_STATE_CONFLICT("ORDER_STATE_CONFLICT", "订单状态不允许该操作", 409),
    ORDER_FULFILLMENT_FAILED("ORDER_FULFILLMENT_FAILED", "订单履约失败，已转人工复核", 409);

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
