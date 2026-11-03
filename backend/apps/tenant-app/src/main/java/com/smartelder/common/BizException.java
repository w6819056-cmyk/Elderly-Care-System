package com.smartelder.common;

/**
 * 业务异常，由全局异常处理器转换为统一响应。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    public static BizException conflict(String message) {
        return new BizException(409, message);
    }
}
