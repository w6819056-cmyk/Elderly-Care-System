package com.smartelder.api.tenant.auth;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant/v1/auth")
public class TenantStaffAuthController {

    private final TenantStaffAuthService authService;
    private final Clock clock = Clock.systemUTC();

    public TenantStaffAuthController(TenantStaffAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        TenantStaffAuthService.LoginResult result = authService.loginForHq(request.account(), request.password());
        return ApiResponse.success(new LoginData(result.accessToken(), "Bearer", result.expiresAt(), result.principal()),
                traceId(httpRequest), clock);
    }

    @GetMapping("/me")
    public ApiResponse<TenantStaffPrincipal> me(Authentication authentication, HttpServletRequest request) {
        TenantStaffPrincipal principal = (TenantStaffPrincipal) authentication.getPrincipal();
        return ApiResponse.success(principal, traceId(request), clock);
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return value == null ? "tr_unknown" : value.toString();
    }

    public record LoginRequest(@NotBlank String account, @NotBlank String password) {}
    public record LoginData(String accessToken, String tokenType, java.time.Instant expiresAt,
                            TenantStaffPrincipal principal) {}
}
