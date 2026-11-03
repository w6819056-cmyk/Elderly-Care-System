package com.smartelder.platform.api;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1")
public class PlatformHealthController {

    private final Clock clock;

    public PlatformHealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health(
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(Map.of("status", "UP"), traceId, clock);
    }
}