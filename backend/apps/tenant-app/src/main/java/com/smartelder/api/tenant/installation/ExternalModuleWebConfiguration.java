package com.smartelder.api.tenant.installation;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.module.api.ModuleOperation;
import com.smartelder.module.runtime.ModuleRuntimeLoader.Loaded;
import com.smartelder.core.licensing.ModuleAccessGuard;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;

@Configuration
public class ExternalModuleWebConfiguration implements WebMvcConfigurer,SmartInitializingSingleton {
 private final RuntimeState runtime;private final ModuleAccessGuard guard;private final ObjectProvider<RequestMappingHandlerMapping> mappings;
 public ExternalModuleWebConfiguration(RuntimeState runtime,ModuleAccessGuard guard,ObjectProvider<RequestMappingHandlerMapping> mappings){this.runtime=runtime;this.guard=guard;this.mappings=mappings;}
 private Optional<Loaded> owner(HandlerMethod handler){return runtime.loaded().stream().filter(m->handler.getBeanType().getName().startsWith("com.smartelder.modules."+m.manifest().module().code().replace('-','_')+".")).findFirst();}
 @Override public void afterSingletonsInstantiated(){
  for(var mapping:mappings)mapping.getHandlerMethods().forEach((info,handler)->owner(handler).ifPresent(module->{
   String prefix="/api/tenant/v1/modules/"+module.manifest().module().code()+"/";
   if(info.getPatternValues().isEmpty()||info.getPatternValues().stream().anyMatch(p->!p.startsWith(prefix)))throw new IllegalStateException("External module HTTP routes must remain in their module namespace");
   var annotation=handler.getMethodAnnotation(ModuleOperation.class);
   var operation=annotation==null?null:module.manifest().module().resourceOperations().stream().filter(o->o.resource().equals(annotation.resource())&&o.operation().equals(annotation.operation())).findFirst().orElse(null);
   if(operation==null)throw new IllegalStateException("External module HTTP operation is not declared");
   if(operation.writeOperation()&&(info.getMethodsCondition().getMethods().isEmpty()||info.getMethodsCondition().getMethods().stream().anyMatch(m->Set.of("GET","HEAD","OPTIONS").contains(m.name()))))throw new IllegalStateException("External write operations must use explicit mutating HTTP methods");
  }));
 }
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
  @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
   if(handler instanceof HandlerMethod method)owner(method).ifPresent(module->{
    var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getPrincipal() instanceof TenantStaffPrincipal principal))throw new com.smartelder.api.tenant.auth.TenantStaffAuthException(401,"UNAUTHORIZED","请登录员工账号");
    var operation=method.getMethodAnnotation(ModuleOperation.class);if(operation==null)throw new IllegalStateException("Missing module operation metadata");
    Long storeId=principal.accessContext().homeStoreId();String requested=request.getParameter("storeId");if(requested!=null&&!requested.isBlank())try{storeId=Long.parseLong(requested);}catch(NumberFormatException e){throw new IllegalArgumentException("门店编号无效");}
    guard.require(module.manifest().module().code(),operation.resource(),operation.operation(),principal.accessContext(),storeId);
   });return true;
  }
 }).addPathPatterns("/api/tenant/v1/modules/**");}
}
