package com.smartelder.platform.api.enterprise;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.enterprise.ActivationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1")
public class ActivationController {

    private final ActivationService activationService;
    private final Clock clock;

    public ActivationController(ActivationService activationService, Clock clock) {
        this.activationService = activationService;
        this.clock = clock;
    }

    @PostMapping("/enterprises/{publicId}/activation-codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.license.manage')")
    public ApiResponse<ActivationService.ActivationCodeResult> createActivationCode(
            @PathVariable String publicId,
            @Valid @RequestBody CreateActivationCodeRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                activationService.createActivationCode(
                        publicId,
                        new ActivationService.CreateActivationCodeCommand(
                                request.ttlHours(),
                                request.maxAttempts()
                        ),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    @PostMapping("/instances/activate")
    public ApiResponse<ActivationService.ActivatedInstance> activate(
            @Valid @RequestBody ActivateInstanceRequest request,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                activationService.activate(
                        new ActivationService.ActivationCommand(
                                request.activationCode(),
                                request.installationId(),
                                request.tenantDatabaseId(),
                                request.publicKeyPem(),
                                request.fingerprint()
                        ),
                        traceId
                ),
                traceId,
                clock
        );
    }

    public record CreateActivationCodeRequest(
            @Min(1) @Max(720) int ttlHours,
            @Min(1) @Max(20) int maxAttempts
    ) {
    }

    public record ActivateInstanceRequest(
            @NotBlank @Size(max = 200) String activationCode,
            @NotBlank @Size(max = 100) String installationId,
            @NotBlank @Size(max = 100) String tenantDatabaseId,
            @NotBlank @Size(max = 4096) String publicKeyPem,
            @NotBlank @Size(max = 160) String fingerprint
    ) {
    }
}
