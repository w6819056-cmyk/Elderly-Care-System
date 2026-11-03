package com.smartelder.api.tenant.installation;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.core.licensing.ModuleMenuService;
import com.smartelder.api.tenant.auth.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Files;

@RestController
public class ModuleRuntimeAssetsController {
 private final RuntimeState runtime;private final ModuleMenuService menus;
 public ModuleRuntimeAssetsController(RuntimeState runtime,ModuleMenuService menus){this.runtime=runtime;this.menus=menus;}
 @GetMapping("/api/tenant/v1/modules/runtime/{code}/entry")
 public ResponseEntity<byte[]> entry(@PathVariable String code,Authentication authentication)throws Exception{
  var actor=((TenantStaffPrincipal)authentication.getPrincipal()).accessContext();
  if(menus.list(actor,null).stream().noneMatch(m->m.moduleCode().equals(code)))throw new TenantStaffAuthException(403,"PERMISSION_DENIED","模块未授权、未启用或当前员工无权使用");
  var loaded=runtime.loaded().stream().filter(m->m.manifest().module().code().equals(code)).findFirst().orElseThrow(()->new IllegalArgumentException("模块未实际加载"));
  var verified=runtime.files().verify(loaded.installation().sha256());var path=runtime.files().payload(verified.packageSha256());runtime.files().verifyPayload(path,verified);
  String entry=loaded.manifest().frontendEntries().get(actor.staffType());if(entry==null)throw new IllegalArgumentException("模块不提供当前工作区页面");
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/javascript;charset=UTF-8")).cacheControl(CacheControl.noStore()).body(verified.file(entry));
 }
}
