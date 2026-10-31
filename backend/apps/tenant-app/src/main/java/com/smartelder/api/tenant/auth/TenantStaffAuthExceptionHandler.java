package com.smartelder.api.tenant.auth;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = TenantStaffAuthController.class)
public class TenantStaffAuthExceptionHandler {

    private final Clock clock = Clock.systemUTC();

    @ExceptionHandler(TenantStaffAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handle(TenantStaffAuthException exception, HttpServletRequest request) {
        ApiResponse<Void> response = new ApiResponse<>(exception.code(), exception.getMessage(), null,
                traceId(request), java.time.OffsetDateTime.now(clock));
        return ResponseEntity.status(exception.httpStatus()).body(response);
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return value == null ? "tr_unknown" : value.toString();
    }
}
