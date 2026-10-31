package com.smartelder.api.tenant;

import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.core.organization.OrganizationException;
import com.smartelder.core.licensing.TenantModuleException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.smartelder.api.tenant")
public class TenantApiExceptionHandler {

    private final Clock clock = Clock.systemUTC();

    @ExceptionHandler(TenantStaffAuthException.class)
    public ResponseEntity<ApiResponse<Void>> auth(TenantStaffAuthException ex, HttpServletRequest request) {
        return response(ex.httpStatus(), ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(OrganizationException.class)
    public ResponseEntity<ApiResponse<Void>> organization(OrganizationException ex, HttpServletRequest request) {
        int status = switch (ex.code()) {
            case "PERMISSION_DENIED", "DATA_SCOPE_DENIED" -> 403;
            case "NOT_FOUND" -> 404;
            case "VERSION_CONFLICT" -> 409;
            default -> 422;
        };
        return response(status, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(TenantModuleException.class)
    public ResponseEntity<ApiResponse<Void>> module(TenantModuleException ex, HttpServletRequest request) {
        int status = switch (ex.code()) {
            case "PERMISSION_DENIED", "DATA_SCOPE_DENIED" -> 403;
            case "NOT_FOUND" -> 404;
            case "MODULE_QUOTA_EXCEEDED", "IDEMPOTENCY_KEY_CONFLICT" -> 409;
            default -> 422;
        };
        return response(status, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> duplicate(DuplicateKeyException ex, HttpServletRequest request) {
        return response(409, "BUSINESS_RULE_VIOLATION", "unique value already exists", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException ex, HttpServletRequest request) {
        return response(422, "BUSINESS_RULE_VIOLATION", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> state(IllegalStateException ex, HttpServletRequest request) {
        String message = ex.getMessage();
        if (message != null && (message.startsWith("missing permission:")
                || message.equals("DATA_SCOPE_DENIED") || message.equals("STORE_ACCESS_DENIED"))) {
            return response(403, "PERMISSION_DENIED", ex.getMessage(), request);
        }
        if ("STORE_NOT_FOUND".equals(message)) {
            return response(404, "NOT_FOUND", "store not found", request);
        }
        return response(409, "BUSINESS_RULE_VIOLATION", ex.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception ex, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "request body is invalid", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> internal(Exception ex, HttpServletRequest request) {
        return response(500, "INTERNAL_ERROR", "internal server error", request);
    }

    private ResponseEntity<ApiResponse<Void>> response(int status, String code, String message,
                                                        HttpServletRequest request) {
        ApiResponse<Void> body = new ApiResponse<>(code, message, null, traceId(request),
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        return ResponseEntity.status(status).body(body);
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return value == null ? "tr_unknown" : value.toString();
    }
}
