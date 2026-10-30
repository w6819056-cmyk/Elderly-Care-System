package com.smartelder.platform.api.commerce;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.commerce.PlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/plans")
public class PlanController {

    private final PlanService planService;
    private final Clock clock;

    public PlanController(PlanService planService, Clock clock) {
        this.planService = planService;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.plan.manage')")
    public ApiResponse<PlanService.PlanView> create(
            @Valid @RequestBody CreatePlanRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        List<PlanService.PlanItemCommand> items = request.items()
                .stream()
                .map(item -> new PlanService.PlanItemCommand(
                        item.moduleCode(),
                        item.billingMode(),
                        item.durationDays(),
                        item.storeQuota()
                ))
                .toList();
        PlanService.PlanView plan = planService.create(
                new PlanService.CreatePlanCommand(
                        request.code(),
                        request.name(),
                        request.description(),
                        request.amount(),
                        request.minimumAmount(),
                        items
                ),
                principal,
                traceId
        );
        return ApiResponse.success(plan, traceId, clock);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.plan.view')")
    public ApiResponse<List<PlanService.PlanView>> list(
            @RequestParam @NotBlank @Size(max = 64) String enterpriseId,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(planService.list(enterpriseId), traceId, clock);
    }

    public record CreatePlanRequest(
            @NotBlank
            @Size(max = 40)
            @Pattern(
                    regexp = "^[a-z][a-z0-9_]*$",
                    message = "必须以小写字母开头，且只能包含小写字母、数字和下划线"
            )
            String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 1000) String description,
            @NotBlank @Size(max = 20) String amount,
            @NotBlank @Size(max = 20) String minimumAmount,
            @NotNull List<@Valid PlanItemRequest> items
    ) {
    }

    public record PlanItemRequest(
            @NotBlank @Size(max = 40) String moduleCode,
            @NotBlank
            @Pattern(regexp = "PERIOD|PERPETUAL", message = "必须为 PERIOD 或 PERPETUAL")
            String billingMode,
            int durationDays,
            int storeQuota
    ) {
    }
}
