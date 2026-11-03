package com.smartelder.platform.api;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.common.PlatformApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PlatformExceptionHandler {

    private final Clock clock;

    public PlatformExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(PlatformApiException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatformApiException(
            PlatformApiException exception,
            HttpServletRequest request
    ) {
        return error(exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return error(ErrorCode.PERMISSION_DENIED, null, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return error(ErrorCode.INVALID_REQUEST, "请求体格式错误", request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return error(ErrorCode.INVALID_REQUEST, message, request);
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(org.springframework.dao.DuplicateKeyException exception,HttpServletRequest request) {
        return error(ErrorCode.CONFLICT,"数据已存在或被并发修改，请刷新后重试",request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        return error(ErrorCode.INTERNAL_ERROR, null, request);
    }

    private ResponseEntity<ApiResponse<Void>> error(
            ErrorCode code,
            String message,
            HttpServletRequest request
    ) {
        String traceId = (String) request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return ResponseEntity.status(code.httpStatus()).body(
                ApiResponse.error(code, message, traceId, clock)
        );
    }
}
