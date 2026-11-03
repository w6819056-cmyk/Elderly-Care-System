package com.smartelder.common;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        OffsetDateTime timestamp
) {

    public static <T> ApiResponse<T> success(T data, String traceId, Clock clock) {
        return new ApiResponse<>(
                ErrorCode.OK.code(),
                ErrorCode.OK.defaultMessage(),
                data,
                traceId,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        );
    }

    public static <T> ApiResponse<T> error(
            ErrorCode errorCode,
            String message,
            String traceId,
            Clock clock
    ) {
        String resolvedMessage = message == null || message.isBlank()
                ? errorCode.defaultMessage()
                : message;
        return new ApiResponse<>(
                errorCode.code(),
                resolvedMessage,
                null,
                traceId,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        );
    }
}