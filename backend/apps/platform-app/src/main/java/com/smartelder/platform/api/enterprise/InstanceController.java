package com.smartelder.platform.api.enterprise;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.api.CachedBodyRequestFilter;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.enterprise.InstanceRequestVerifier;
import com.smartelder.platform.core.enterprise.InstanceService;
import com.smartelder.platform.core.enterprise.LicenseSigningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/instances")
public class InstanceController {

    private final InstanceRequestVerifier requestVerifier;
    private final InstanceService instanceService;
    private final Clock clock;

    public InstanceController(
            InstanceRequestVerifier requestVerifier,
            InstanceService instanceService,
            Clock clock
    ) {
        this.requestVerifier = requestVerifier;
        this.instanceService = instanceService;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.license.view')")
    public ApiResponse<List<InstanceService.InstanceView>> list(
            @RequestParam String enterpriseId,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                instanceService.listByEnterprise(enterpriseId),
                traceId,
                clock
        );
    }

    @PostMapping("/heartbeat")
    public ApiResponse<InstanceService.HeartbeatResult> heartbeat(
            @RequestHeader(value = "X-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Instance-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Instance-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Instance-Signature", required = false) String signature,
            @Valid @RequestBody HeartbeatRequest body,
            HttpServletRequest request,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        InstanceRequestVerifier.VerifiedInstance instance = requestVerifier.verify(
                verificationInput(instanceId, timestamp, nonce, signature, request)
        );
        return ApiResponse.success(
                instanceService.heartbeat(
                        instance,
                        new InstanceService.HeartbeatCommand(
                                body.licenseVersion(),
                                body.clientTime()
                        ),
                        request.getRemoteAddr()
                ),
                traceId,
                clock
        );
    }

    @GetMapping("/{publicId}/license")
    public ApiResponse<LicenseSigningService.SignedLicense> license(
            @PathVariable String publicId,
            @RequestHeader(value = "X-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Instance-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Instance-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Instance-Signature", required = false) String signature,
            HttpServletRequest request,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        InstanceRequestVerifier.VerifiedInstance instance = requestVerifier.verify(
                verificationInput(instanceId, timestamp, nonce, signature, request)
        );
        if (!instance.publicId().equals(publicId)) {
            throw new PlatformApiException(
                    ErrorCode.INSTANCE_SIGNATURE_INVALID,
                    "实例签名与目标实例不匹配"
            );
        }
        return ApiResponse.success(
                instanceService.currentLicense(instance),
                traceId,
                clock
        );
    }

    private static InstanceRequestVerifier.VerificationInput verificationInput(
            String instanceId,
            String timestamp,
            String nonce,
            String signature,
            HttpServletRequest request
    ) {
        Object cachedBody = request.getAttribute(CachedBodyRequestFilter.CACHED_BODY_ATTRIBUTE);
        byte[] body = cachedBody instanceof byte[] bytes ? bytes : new byte[0];
        return new InstanceRequestVerifier.VerificationInput(
                instanceId,
                request.getMethod(),
                request.getRequestURI(),
                timestamp,
                nonce,
                signature,
                body
        );
    }

    public record HeartbeatRequest(
            @Min(0) long licenseVersion,
            @NotNull Instant clientTime
    ) {
    }
}
