package com.smartelder.platform.api.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.*;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.enterprise.InstanceRequestVerifier;
import com.smartelder.platform.core.commerce.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/platform/v1/instances/{instanceId}/commerce")
public class InstanceCommerceController {
 private final InstanceRequestVerifier verifier;private final SalesOrderService orders;private final JdbcClient jdbc;private final ObjectMapper mapper;private final Clock clock;private final boolean mockEnabled;
 public InstanceCommerceController(InstanceRequestVerifier v,SalesOrderService o,JdbcClient j,ObjectMapper m,Clock c,@Value("${platform.commerce.mock-payment-enabled:false}") boolean mock){verifier=v;orders=o;jdbc=j;mapper=m;clock=c;mockEnabled=mock;}
 private InstanceRequestVerifier.VerifiedInstance verify(String id,HttpServletRequest r,String body){
  byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length>8192)throw invalid("购买请求过大");
  var actor=verifier.verify(new InstanceRequestVerifier.VerificationInput(r.getHeader("X-Instance-Id"),r.getMethod(),r.getRequestURI(),r.getHeader("X-Instance-Timestamp"),r.getHeader("X-Instance-Nonce"),r.getHeader("X-Instance-Signature"),bytes));
  if(!actor.publicId().equals(id))throw new PlatformApiException(ErrorCode.INSTANCE_SIGNATURE_INVALID,"实例身份与请求目标不一致");return actor;
 }
 private PlatformApiException invalid(String message){return new PlatformApiException(ErrorCode.INVALID_REQUEST,message);}
 private Object ok(Object data,HttpServletRequest r){return ApiResponse.success(data,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),clock);}
 public record Card(long cardId,Long productId,Long planId,String planCode,String moduleCode,String name,String description,String imageUrl,int sortOrder,BigDecimal amount,String billingMode,Integer durationDays,Integer storeQuota,boolean perpetualOwned){}
 @GetMapping("/catalog") public Object catalog(@PathVariable String instanceId,HttpServletRequest r){var a=verify(instanceId,r,"");
  List<Card> cards=jdbc.sql("""
   SELECT c.id,c.product_id,c.plan_id,b.code AS plan_code,m.code AS module_code,COALESCE(p.name,b.name) AS name,
   COALESCE(p.description,b.description) AS description,c.image_url,c.sort_order,COALESCE(p.amount,b.amount) AS amount,
   p.billing_mode,p.duration_days,p.store_quota,
   CASE WHEN p.id IS NOT NULL THEN EXISTS(SELECT 1 FROM pf_entitlement e WHERE e.enterprise_id=:enterprise AND e.module_id=p.module_id AND e.perpetual=TRUE AND e.status='ACTIVE')
   ELSE NOT EXISTS(SELECT 1 FROM pf_plan_module pm WHERE pm.plan_id=b.id AND NOT EXISTS(SELECT 1 FROM pf_entitlement e WHERE e.enterprise_id=:enterprise AND e.module_id=pm.module_id AND e.perpetual=TRUE AND e.status='ACTIVE')) END AS owned
   FROM pf_sale_card c LEFT JOIN pf_sale_product p ON p.id=c.product_id LEFT JOIN pf_plan b ON b.id=c.plan_id
   LEFT JOIN pf_module_definition m ON m.id=p.module_id
   WHERE c.enabled=TRUE AND ((p.id IS NOT NULL AND p.sale_status='ON_SALE' AND m.sale_status='ON_SALE') OR (b.id IS NOT NULL AND b.sale_status='ON_SALE')) ORDER BY c.sort_order,c.id
   """).param("enterprise",a.enterpriseId()).query((rs,n)->new Card(rs.getLong("id"),(Long)rs.getObject("product_id"),(Long)rs.getObject("plan_id"),rs.getString("plan_code"),rs.getString("module_code"),rs.getString("name"),rs.getString("description"),rs.getString("image_url"),rs.getInt("sort_order"),rs.getBigDecimal("amount"),rs.getString("billing_mode"),(Integer)rs.getObject("duration_days"),(Integer)rs.getObject("store_quota"),rs.getBoolean("owned"))).list();
  return ok(Map.of("cards",cards.stream().filter(c->!c.perpetualOwned()).toList(),"mockPaymentEnabled",mockEnabled),r);
 }
 public record Purchase(Long productId,Long planId,String couponCode,String idempotencyKey){}
 private Purchase parse(String body){try{var b=mapper.readValue(body,Purchase.class);if(b==null||(b.productId()==null)==(b.planId()==null))throw invalid("请选择一个商品或套餐");return b;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw invalid("购买请求格式错误");}}
 private SalesOrderService.CreateOrderCommand command(Purchase b,String enterprise){
  if(b.productId()!=null){return jdbc.sql("SELECT m.code,p.billing_mode,p.duration_days,p.store_quota FROM pf_sale_product p JOIN pf_module_definition m ON m.id=p.module_id WHERE p.id=:id AND p.sale_status='ON_SALE'").param("id",b.productId()).query((rs,n)->{
   var item=new OrderQuoteService.QuoteItem(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4));return new SalesOrderService.CreateOrderCommand(enterprise,null,List.of(item),new OrderQuoteService.BillingPeriod(item.billingMode(),item.durationDays(),item.storeQuota()));
  }).optional().orElseThrow(()->invalid("商品不存在或已停售"));}
  String code=jdbc.sql("SELECT code FROM pf_plan WHERE id=:id AND sale_status='ON_SALE'").param("id",b.planId()).query(String.class).optional().orElseThrow(()->invalid("套餐不存在或已停售"));return new SalesOrderService.CreateOrderCommand(enterprise,code,List.of(),null);
 }
 @PostMapping("/quote") public Object quote(@PathVariable String instanceId,@RequestBody String body,HttpServletRequest r){var a=verify(instanceId,r,body);var b=parse(body);return ok(orders.quoteForEnterprise(command(b,a.enterprisePublicId()),b.couponCode()),r);}
 @PostMapping("/orders") public Object create(@PathVariable String instanceId,@RequestBody String body,HttpServletRequest r)throws Exception{var a=verify(instanceId,r,body);var b=parse(body);var canonical=mapper.writeValueAsString(b);return ok(orders.createFromInstance(command(b,a.enterprisePublicId()),a.id(),a.publicId(),b.idempotencyKey(),canonical,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME))),r);}
 @GetMapping("/orders") public Object list(@PathVariable String instanceId,HttpServletRequest r){var a=verify(instanceId,r,"");return ok(orders.list(a.enterprisePublicId(),null,1,100),r);}
 @GetMapping("/orders/{id}") public Object get(@PathVariable String instanceId,@PathVariable String id,HttpServletRequest r){var a=verify(instanceId,r,"");return ok(orders.getForEnterprise(id,a.enterprisePublicId()),r);}
 @PostMapping("/orders/{id}/pay-simulated") public Object pay(@PathVariable String instanceId,@PathVariable String id,@RequestBody String body,HttpServletRequest r){var a=verify(instanceId,r,body);if(!mockEnabled)throw new PlatformApiException(ErrorCode.PERMISSION_DENIED,"平台未开启模拟支付，真实支付渠道尚未接入");return ok(orders.payFromInstance(id,a.enterprisePublicId(),a.publicId(),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME))),r);}
 @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String instanceId,@PathVariable String id,@RequestBody String body,HttpServletRequest r){var a=verify(instanceId,r,body);return ok(orders.cancelFromInstance(id,a.enterprisePublicId(),a.publicId(),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME))),r);}
}
