package com.smartelder.platform.api.auth;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformAuthService;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/auth")
public class PlatformAuthController {

    private final PlatformAuthService authService;
    private final Clock clock;

    public PlatformAuthController(PlatformAuthService authService, Clock clock) {
        this.authService = authService;
        this.clock = clock;
    }

    @PostMapping("/login")
    public ApiResponse<PlatformAuthService.LoginResult> login(
            @Valid @RequestBody LoginRequest request,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                authService.login(request.username(), request.password()),
                traceId,
                clock
        );
    }

    @GetMapping("/me")
    public ApiResponse<PlatformPrincipal> me(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(principal, traceId, clock);
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 200) String password
    ) {
    }
}
