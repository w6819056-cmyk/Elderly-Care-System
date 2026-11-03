package com.smartelder.platform.api.catalog;

import com.smartelder.common.*;
import com.smartelder.platform.core.common.*;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/v1/storefront")
public class ModuleStorefrontController {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final PlatformAuditService audit;
    public ModuleStorefrontController(JdbcClient jdbc, Clock clock, PlatformAuditService audit) {
        this.jdbc=jdbc; this.clock=clock; this.audit=audit;
    }
    @GetMapping
    @PreAuthorize("hasAuthority('platform.module.view')")
    public ApiResponse<List<Card>> list(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String trace) {
        var cards=jdbc.sql("SELECT code, name, sale_status, storefront_title, storefront_description, storefront_image, storefront_order, storefront_enabled FROM pf_module_definition ORDER BY storefront_order, id")
            .query((rs,n)->new Card(rs.getString("code"),rs.getString("name"),rs.getString("sale_status"),rs.getString("storefront_title"),rs.getString("storefront_description"),rs.getString("storefront_image"),rs.getInt("storefront_order"),rs.getBoolean("storefront_enabled"))).list();
        return ApiResponse.success(cards,trace,clock);
    }
    @PutMapping("/{code}")
    @Transactional
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public ApiResponse<Void> save(@PathVariable String code,@Valid @RequestBody Edit edit,
        @AuthenticationPrincipal PlatformPrincipal actor,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String trace) {
        String image=edit.imageUrl()==null?"":edit.imageUrl().trim();
        if (!image.isEmpty() && !(image.startsWith("https://") || (image.startsWith("/images/") && !image.contains(".."))))
            throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"图片地址须为 HTTPS 地址或本站 /images/ 图片路径");
        int changed=jdbc.sql("UPDATE pf_module_definition SET storefront_title=:title, storefront_description=:description, storefront_image=:image, storefront_order=:sort, storefront_enabled=:enabled, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE code=:code")
            .param("title",edit.title().trim()).param("description",edit.description().trim()).param("image",image).param("sort",edit.sortOrder()).param("enabled",edit.enabled()).param("code",code).update();
        if(changed!=1)throw new PlatformApiException(ErrorCode.NOT_FOUND,"绑定模块不存在");
        audit.record(actor.userId(),actor.publicId(),"MODULE_STOREFRONT_UPDATED","MODULE",code,null,trace);
        return ApiResponse.success(null,trace,clock);
    }
    public record Profile(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=1000) String description) {}
    @PutMapping("/{code}/profile") @Transactional
    @PreAuthorize("hasAuthority('platform.module.manage')")
    public Object profile(@PathVariable String code,@Valid @RequestBody Profile p,@AuthenticationPrincipal PlatformPrincipal actor,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String trace) {
        if(jdbc.sql("UPDATE pf_module_definition SET name=:name,description=:description,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE code=:code").param("name",p.name().trim()).param("description",p.description().trim()).param("code",code).update()!=1)throw new PlatformApiException(ErrorCode.NOT_FOUND,"模块不存在");
        audit.record(actor.userId(),actor.publicId(),"MODULE_PROFILE_UPDATED","MODULE",code,null,trace);
        return ApiResponse.success(null,trace,clock);
    }
    public record Card(String moduleCode,String moduleName,String saleStatus,String title,String description,String imageUrl,int sortOrder,boolean enabled) {}
    public record Edit(@NotBlank @Size(max=100) String title,@NotNull @Size(max=1000) String description,@Size(max=2000) String imageUrl,@Min(0) @Max(100000) int sortOrder,boolean enabled) {}
}
