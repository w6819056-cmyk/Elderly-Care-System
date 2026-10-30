package com.smartelder.platform.api.catalog;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.catalog.ModuleCatalogService;
import com.smartelder.platform.core.catalog.ModuleVersionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1/modules")
public class ModuleCatalogController {

    private final ModuleCatalogService catalogService;
    private final ModuleVersionService versionService;
    private final Clock clock;

    public ModuleCatalogController(
            ModuleCatalogService catalogService,
            ModuleVersionService versionService,
            Clock clock
    ) {
        this.catalogService = catalogService;
        this.versionService = versionService;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.module.view')")
    public ApiResponse<ModuleCatalogService.PageResult<ModuleCatalogService.ModuleView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false)
            @Pattern(regexp = "DRAFT|ON_SALE|OFF_SALE", message = "状态值无效")
            String status,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                catalogService.list(page, pageSize, status),
                traceId,
                clock
        );
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAuthority('platform.module.view')")
    public ApiResponse<ModuleCatalogService.ModuleView> detail(
            @PathVariable String code,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(catalogService.findByCode(code), traceId, clock);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public ApiResponse<ModuleCatalogService.ModuleView> create(
            @Valid @RequestBody CreateModuleRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                catalogService.create(toCommand(request), principal, traceId),
                traceId,
                clock
        );
    }

    @PutMapping("/{code}/status")
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public ApiResponse<ModuleCatalogService.ModuleView> changeStatus(
            @PathVariable String code,
            @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                catalogService.changeStatus(code, request.status(), principal, traceId),
                traceId,
                clock
        );
    }

    @PostMapping("/{code}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public ApiResponse<ModuleVersionService.VersionView> addVersion(
            @PathVariable String code,
            @Valid @RequestBody AddVersionRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(
                versionService.addVersion(
                        code,
                        new ModuleVersionService.CreateVersionCommand(
                                request.version(),
                                request.minCoreVersion()
                        ),
                        principal,
                        traceId
                ),
                traceId,
                clock
        );
    }

    @PutMapping("/{code}/dependencies")
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public ApiResponse<List<ModuleCatalogService.DependencyView>> replaceDependencies(
            @PathVariable String code,
            @Valid @RequestBody ReplaceDependenciesRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        List<ModuleCatalogService.DependencyCommand> dependencies = request.dependencies()
                .stream()
                .map(dependency -> new ModuleCatalogService.DependencyCommand(
                        dependency.moduleCode(),
                        dependency.type(),
                        dependency.minimumVersion()
                ))
                .toList();
        return ApiResponse.success(
                catalogService.replaceDependencies(code, dependencies, principal, traceId),
                traceId,
                clock
        );
    }

    @PutMapping("/{code}/prices")
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public ApiResponse<List<ModuleCatalogService.PriceView>> replacePrices(
            @PathVariable String code,
            @Valid @RequestBody ReplacePricesRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        List<ModuleCatalogService.PriceCommand> prices = request.prices()
                .stream()
                .map(price -> new ModuleCatalogService.PriceCommand(
                        price.billingMode(),
                        price.durationDays(),
                        price.amount(),
                        price.currency(),
                        price.storeQuota(),
                        price.trialDays()
                ))
                .toList();
        return ApiResponse.success(
                catalogService.replacePrices(code, prices, principal, traceId),
                traceId,
                clock
        );
    }

    private static ModuleCatalogService.CreateModuleCommand toCommand(CreateModuleRequest request) {
        List<ModuleCatalogService.DependencyCommand> dependencies = request.dependencies()
                .stream()
                .map(dependency -> new ModuleCatalogService.DependencyCommand(
                        dependency.moduleCode(),
                        dependency.type(),
                        dependency.minimumVersion()
                ))
                .toList();
        List<ModuleCatalogService.PriceCommand> prices = request.prices()
                .stream()
                .map(price -> new ModuleCatalogService.PriceCommand(
                        price.billingMode(),
                        price.durationDays(),
                        price.amount(),
                        price.currency(),
                        price.storeQuota(),
                        price.trialDays()
                ))
                .toList();
        return new ModuleCatalogService.CreateModuleCommand(
                request.code(),
                request.name(),
                request.description(),
                request.menuOrder(),
                request.trialEnabled(),
                dependencies,
                prices
        );
    }

    public record CreateModuleRequest(
            @NotBlank
            @Size(min = 2, max = 32)
            @Pattern(
                    regexp = "^[a-z][a-z0-9-]{1,31}$",
                    message = "必须以小写字母开头，且只能包含小写字母、数字和连字符，长度 2～32"
            )
            String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 1000) String description,
            @Min(1) int menuOrder,
            boolean trialEnabled,
            @NotNull List<@Valid DependencyRequest> dependencies,
            @NotNull List<@Valid PriceRequest> prices
    ) {
    }

    public record DependencyRequest(
            @NotBlank
            @Size(min = 2, max = 32)
            @Pattern(
                    regexp = "^[a-z][a-z0-9-]{1,31}$",
                    message = "必须以小写字母开头，且只能包含小写字母、数字和连字符，长度 2～32"
            )
            String moduleCode,
            @NotBlank
            @Pattern(regexp = "REQUIRED|OPTIONAL", message = "必须为 REQUIRED 或 OPTIONAL")
            String type,
            @NotBlank @Size(max = 30) String minimumVersion
    ) {
    }

    public record PriceRequest(
            @NotBlank
            @Pattern(regexp = "PERIOD|PERPETUAL", message = "必须为 PERIOD 或 PERPETUAL")
            String billingMode,
            @Min(0) int durationDays,
            @NotBlank @Size(max = 20) String amount,
            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "必须为三位币种代码")
            String currency,
            @Min(1) int storeQuota,
            Integer trialDays
    ) {
    }

    public record ChangeStatusRequest(
            @NotBlank
            @Pattern(
                    regexp = "DRAFT|ON_SALE|OFF_SALE",
                    message = "必须为 DRAFT、ON_SALE 或 OFF_SALE"
            )
            String status
    ) {
    }

    public record AddVersionRequest(
            @NotBlank @Size(max = 30) String version,
            @NotBlank @Size(max = 30) String minCoreVersion
    ) {
    }

    public record ReplaceDependenciesRequest(
            @NotNull List<@Valid DependencyRequest> dependencies
    ) {
    }

    public record ReplacePricesRequest(
            @NotNull List<@Valid PriceRequest> prices
    ) {
    }
}
