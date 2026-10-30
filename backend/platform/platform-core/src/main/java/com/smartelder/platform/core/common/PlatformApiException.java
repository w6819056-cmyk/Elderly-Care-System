package com.smartelder.platform.core.common;

import com.smartelder.common.ErrorCode;
import java.util.Objects;

public final class PlatformApiException extends RuntimeException {

    private final ErrorCode code;

    public PlatformApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public PlatformApiException(ErrorCode code, String message, Throwable cause) {
        super(resolveMessage(code, message), cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public ErrorCode code() {
        return code;
    }

    public ErrorCode getCode() {
        return code;
    }

    private static String resolveMessage(ErrorCode code, String message) {
        Objects.requireNonNull(code, "code must not be null");
        return message == null || message.isBlank() ? code.defaultMessage() : message;
    }
}
