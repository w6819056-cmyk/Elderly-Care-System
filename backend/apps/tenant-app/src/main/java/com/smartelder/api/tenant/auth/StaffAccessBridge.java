package com.smartelder.api.tenant.auth;
import com.smartelder.core.api.access.*;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;

@Component
public class StaffAccessBridge implements StaffAccessApi {
 public TenantAccessContext current(){
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  if(authentication==null||!(authentication.getPrincipal() instanceof TenantStaffPrincipal principal))throw new TenantStaffAuthException(401,"UNAUTHORIZED","请登录员工账号");
  return principal.accessContext();
 }
}
