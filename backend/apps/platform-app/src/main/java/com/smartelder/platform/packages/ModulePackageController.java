package com.smartelder.platform.packages;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.module.packaging.ModulePackageVerifier;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/v1/module-packages")
public class ModulePackageController {
 private final ModulePackageStore store;
 public ModulePackageController(ModulePackageStore store){this.store=store;}
 @GetMapping @PreAuthorize("hasAuthority('platform.module.view')") public Object list(HttpServletRequest r){return ApiResponse.success(store.list(),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());}
 @PostMapping(consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) @PreAuthorize("hasAuthority('platform.module.manage')")
 public Object upload(HttpServletRequest r,@AuthenticationPrincipal PlatformPrincipal actor)throws IOException{
  if(r.getContentLengthLong()>ModulePackageVerifier.MAX_ARCHIVE_BYTES)throw new com.smartelder.platform.core.common.PlatformApiException(com.smartelder.common.ErrorCode.INVALID_REQUEST,"模块包不得超过64MiB");
  byte[] bytes=r.getInputStream().readNBytes(ModulePackageVerifier.MAX_ARCHIVE_BYTES+1);
  return ApiResponse.success(store.upload(bytes,actor,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME))),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());
 }
 @GetMapping("/{code}/{version}/download") @PreAuthorize("hasAuthority('platform.module.view')")
 public ResponseEntity<byte[]> download(@PathVariable String code,@PathVariable String version){return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=module.caremod").header(HttpHeaders.CACHE_CONTROL,"no-store").body(store.download(code,version));}
 @PostMapping("/{code}/{version}/publish") @PreAuthorize("hasAuthority('platform.module.manage')")
 public Object publish(@PathVariable String code,@PathVariable String version,@AuthenticationPrincipal PlatformPrincipal actor,HttpServletRequest r){store.publish(code,version,actor,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)));return ApiResponse.success(null,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());}
}
