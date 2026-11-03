package com.smartelder.platform.packages;
import com.smartelder.common.*;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.enterprise.InstanceRequestVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/v1/instances/{id}/module-packages")
public class InstancePackageController {
 private final InstanceRequestVerifier verifier;private final ModulePackageStore store;
 public InstancePackageController(InstanceRequestVerifier verifier,ModulePackageStore store){this.verifier=verifier;this.store=store;}
 private InstanceRequestVerifier.VerifiedInstance verify(String id,HttpServletRequest r){
  var actor=verifier.verify(new InstanceRequestVerifier.VerificationInput(r.getHeader("X-Instance-Id"),r.getMethod(),r.getRequestURI(),r.getHeader("X-Instance-Timestamp"),r.getHeader("X-Instance-Nonce"),r.getHeader("X-Instance-Signature"),new byte[0]));
  if(!actor.publicId().equals(id))throw new PlatformApiException(ErrorCode.INSTANCE_SIGNATURE_INVALID,"实例身份与下载目标不一致");return actor;
 }
 @GetMapping public Object list(@PathVariable String id,HttpServletRequest r){var actor=verify(id,r);return ApiResponse.success(store.available(actor.enterpriseId()),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());}
 @GetMapping("/{code}/{version}") public ResponseEntity<byte[]> download(@PathVariable String id,@PathVariable String code,@PathVariable String version,HttpServletRequest r){var actor=verify(id,r);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CACHE_CONTROL,"no-store").body(store.downloadForEnterprise(actor.enterpriseId(),code,version));}
}
