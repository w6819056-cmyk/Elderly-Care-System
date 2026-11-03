package com.smartelder.api.tenant.commerce;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import com.smartelder.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/tenant/v1/hq/shop")
public class TenantCommerceController {
 private final TenantCommerceService service;public TenantCommerceController(TenantCommerceService s){service=s;}
 private com.smartelder.core.api.access.TenantAccessContext actor(Authentication a){return ((TenantStaffPrincipal)a.getPrincipal()).accessContext();}
 private String trace(HttpServletRequest r){return String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME));}
 private Object ok(Object data,HttpServletRequest r){return ApiResponse.success(data,trace(r),Clock.systemUTC());}
 @GetMapping("/configuration") public Object configuration(Authentication a,HttpServletRequest r){return ok(service.configuration(actor(a)),r);}
 @GetMapping("/catalog") public Object catalog(Authentication a,HttpServletRequest r){return ok(service.call("GET","/catalog",null,actor(a),trace(r)),r);}
 @GetMapping("/orders") public Object list(Authentication a,HttpServletRequest r){return ok(service.call("GET","/orders",null,actor(a),trace(r)),r);}
 public record Purchase(@Positive Long productId,@Positive Long planId,@Size(max=64) String couponCode,@Size(max=80) String idempotencyKey){}
 private Map<String,Object> body(Purchase b,boolean create){if((b.productId()==null)==(b.planId()==null))throw new IllegalArgumentException("请选择一个商品或套餐");var out=new LinkedHashMap<String,Object>();out.put("productId",b.productId());out.put("planId",b.planId());out.put("couponCode",b.couponCode());if(create){if(b.idempotencyKey()==null||!b.idempotencyKey().matches("[a-zA-Z0-9_-]{8,80}"))throw new IllegalArgumentException("下单请求标识无效，请刷新后重试");out.put("idempotencyKey",b.idempotencyKey());}return out;}
 @PostMapping("/quote") public Object quote(@Valid @RequestBody Purchase b,Authentication a,HttpServletRequest r){return ok(service.call("POST","/quote",body(b,false),actor(a),trace(r)),r);}
 @PostMapping("/orders") public Object create(@Valid @RequestBody Purchase b,Authentication a,HttpServletRequest r){return ok(service.call("POST","/orders",body(b,true),actor(a),trace(r)),r);}
 @GetMapping("/orders/{id}") public Object get(@PathVariable String id,Authentication a,HttpServletRequest r){return ok(service.call("GET","/orders/"+id,null,actor(a),trace(r)),r);}
 @PostMapping("/orders/{id}/pay-simulated") public Object pay(@PathVariable String id,Authentication a,HttpServletRequest r){return ok(service.call("POST","/orders/"+id+"/pay-simulated",Map.of(),actor(a),trace(r)),r);}
 @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,Authentication a,HttpServletRequest r){return ok(service.call("POST","/orders/"+id+"/cancel",Map.of(),actor(a),trace(r)),r);}
 @PostMapping("/sync-license") public Object sync(Authentication a,HttpServletRequest r){return ok(service.sync(actor(a)),r);}
}
