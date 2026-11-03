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

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {"com.smartelder.api.tenant", "com.smartelder.modules"})
public class TenantApiExceptionHandler {

    private final Clock clock = Clock.systemUTC();
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> explicitStatus(org.springframework.web.server.ResponseStatusException ex,HttpServletRequest request){
        int status=ex.getStatusCode().value();String code=switch(status){case 401->"UNAUTHORIZED";case 403->"PERMISSION_DENIED";case 404->"NOT_FOUND";case 409->"BUSINESS_RULE_VIOLATION";default->"INVALID_REQUEST";};
        return response(status,code,ex.getReason()==null?"模块请求被拒绝":ex.getReason(),request);
    }

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
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> concurrent(org.springframework.dao.ConcurrencyFailureException ex,HttpServletRequest request){
        return response(409,"BUSINESS_RULE_VIOLATION","数据正在被其他操作修改，请刷新后重试",request);
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
