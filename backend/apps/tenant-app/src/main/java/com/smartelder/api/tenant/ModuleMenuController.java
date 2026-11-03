package com.smartelder.api.tenant;

import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.core.licensing.ModuleMenuService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant/v1/modules")
public class ModuleMenuController {
    private final ModuleMenuService menus;
    private final Clock clock = Clock.systemUTC();
    public ModuleMenuController(ModuleMenuService menus) { this.menus = menus; }
    @GetMapping("/menus")
    public ApiResponse<List<ModuleMenuService.MenuView>> list(Authentication authentication,
            @RequestParam(required = false) Long storeId, HttpServletRequest request) {
        var actor = ((TenantStaffPrincipal) authentication.getPrincipal()).accessContext();
        Object trace = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        return ApiResponse.success(menus.list(actor, storeId), trace == null ? "tr_unknown" : trace.toString(), clock);
    }
}
