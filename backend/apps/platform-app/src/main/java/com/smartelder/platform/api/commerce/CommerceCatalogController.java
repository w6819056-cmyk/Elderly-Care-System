package com.smartelder.platform.api.commerce;

import com.smartelder.common.*;
import com.smartelder.platform.core.common.*;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/v1/commerce")
public class CommerceCatalogController {
 private final JdbcClient jdbc;
 private final PlatformAuditService audit;
 private final Clock clock;
 public CommerceCatalogController(JdbcClient jdbc,PlatformAuditService audit,Clock clock){this.jdbc=jdbc;this.audit=audit;this.clock=clock;}
 private PlatformApiException invalid(String message){return new PlatformApiException(ErrorCode.INVALID_REQUEST,message);}
 private <T> ApiResponse<T> ok(T value,String trace){return ApiResponse.success(value,trace,clock);}
 private void record(PlatformPrincipal actor,String action,String id,String trace){audit.record(actor.userId(),actor.publicId(),action,"COMMERCE",id,null,trace);}
 @GetMapping("/products") @PreAuthorize("hasAuthority('platform.module.view')")
 public Object products(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String trace){return ok(jdbc.sql("SELECT p.*,m.code AS moduleCode,m.name AS moduleName FROM pf_sale_product p JOIN pf_module_definition m ON m.id=p.module_id ORDER BY p.id DESC").query().listOfRows(),trace);}
 @GetMapping("/plan-options") @PreAuthorize("hasAuthority('platform.plan.view')")
 public Object planOptions(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){return ok(jdbc.sql("SELECT id,code,name,description,amount,sale_status FROM pf_plan ORDER BY id DESC").query().listOfRows(),t);}
 @GetMapping("/dashboard") @PreAuthorize("hasAuthority('platform.enterprise.view') and hasAuthority('platform.module.view') and hasAuthority('platform.order.view')")
 public Object dashboard(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){
  var data=new LinkedHashMap<String,Object>();
  data.put("enterprises",jdbc.sql("SELECT COUNT(*) FROM pf_enterprise").query(Long.class).single());
  data.put("pendingApplications",jdbc.sql("SELECT COUNT(*) FROM pf_enterprise_application WHERE status='PENDING'").query(Long.class).single());
  data.put("modules",jdbc.sql("SELECT COUNT(*) FROM pf_module_definition").query(Long.class).single());
  data.put("versions",jdbc.sql("SELECT COUNT(*) FROM pf_module_version").query(Long.class).single());
  data.put("instances",jdbc.sql("SELECT COUNT(*) FROM pf_deployment_instance").query(Long.class).single());
  data.put("orders",jdbc.sql("SELECT COUNT(*) FROM pf_subscription_order").query(Long.class).single());
  data.put("revenue",jdbc.sql("SELECT COALESCE(SUM(payable_amount),0) FROM pf_subscription_order WHERE status='FULFILLED'").query(BigDecimal.class).single());
  data.put("enterpriseStatuses",jdbc.sql("SELECT status,COUNT(*) AS total FROM pf_enterprise GROUP BY status").query().listOfRows());
  data.put("orderStatuses",jdbc.sql("SELECT status,COUNT(*) AS total FROM pf_subscription_order GROUP BY status").query().listOfRows());
  data.put("recentOrders",jdbc.sql("SELECT order_no AS orderNo,status,payable_amount AS amount,created_at AS createdAt FROM pf_subscription_order ORDER BY id DESC LIMIT 6").query().listOfRows());
  data.put("instanceHealth",jdbc.sql("SELECT public_id AS publicId,status,last_heartbeat_at AS lastHeartbeatAt FROM pf_deployment_instance ORDER BY last_heartbeat_at DESC LIMIT 8").query().listOfRows());
  return ok(data,t);
 }
 public record Product(@NotBlank @Size(max=40) String moduleCode,@NotBlank @Size(max=100) String name,@NotNull @Size(max=1000) String description,@Pattern(regexp="PERIOD|PERPETUAL") @NotNull String billingMode,@Min(0) int durationDays,@NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,@Min(1) int storeQuota){}
 @PostMapping("/products") @PreAuthorize("hasAuthority('platform.module.manage')") @Transactional
 public Object createProduct(@Valid @RequestBody Product p,@AuthenticationPrincipal PlatformPrincipal a,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){return saveProduct(null,p,a,t);}
 @PutMapping("/products/{id}") @PreAuthorize("hasAuthority('platform.module.manage')") @Transactional
 public Object editProduct(@PathVariable long id,@Valid @RequestBody Product p,@AuthenticationPrincipal PlatformPrincipal a,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){return saveProduct(id,p,a,t);}
 private Object saveProduct(Long id,Product p,PlatformPrincipal a,String t){
  if((p.billingMode().equals("PERIOD")&&p.durationDays()<1)||(p.billingMode().equals("PERPETUAL")&&p.durationDays()!=0))throw invalid("按期至少1天，永久期限必须为0");
  long module=jdbc.sql("SELECT id FROM pf_module_definition WHERE code=:code FOR UPDATE").param("code",p.moduleCode()).query(Long.class).optional().orElseThrow(()->invalid("绑定模块不存在"));
  if(id!=null){var old=jdbc.sql("SELECT module_id,billing_mode,duration_days,store_quota FROM pf_sale_product WHERE id=:id FOR UPDATE").param("id",id).query().listOfRows().stream().findFirst().orElseThrow(()->invalid("商品不存在"));
   if(((Number)old.get("module_id")).longValue()!=module)throw invalid("已创建商品不可换绑模块，请新建商品");
   boolean referenced=jdbc.sql("SELECT COUNT(*) FROM pf_plan_module WHERE module_id=:module AND billing_mode=:mode AND duration_days=:days").param("module",module).param("mode",old.get("billing_mode")).param("days",old.get("duration_days")).query(Long.class).single()>0;
   if(referenced&&(!p.billingMode().equals(old.get("billing_mode"))||p.durationDays()!=((Number)old.get("duration_days")).intValue()||p.storeQuota()!=((Number)old.get("store_quota")).intValue()))throw invalid("商品已被套餐引用，变更计费方式、期限或名额前请先处理套餐引用");
   if(!p.billingMode().equals(old.get("billing_mode"))||p.durationDays()!=((Number)old.get("duration_days")).intValue()) {
    jdbc.sql("DELETE FROM pf_module_price WHERE module_id=:module AND billing_mode=:mode AND duration_days=:days").param("module",module).param("mode",old.get("billing_mode")).param("days",old.get("duration_days")).update();
   }
  }
  if(jdbc.sql("SELECT COUNT(*) FROM pf_sale_product WHERE module_id=:module AND billing_mode=:mode AND duration_days=:days AND id<>:id").param("module",module).param("mode",p.billingMode()).param("days",p.durationDays()).param("id",id==null?0:id).query(Long.class).single()>0)throw invalid("同一模块的该计费期限已有商品，请修改已有商品");
  if(id==null){jdbc.sql("INSERT INTO pf_sale_product(module_id,name,description,billing_mode,duration_days,amount,store_quota) VALUES(:module,:name,:description,:mode,:days,:amount,:quota)")
   .param("module",module).param("name",p.name().trim()).param("description",p.description().trim()).param("mode",p.billingMode()).param("days",p.durationDays()).param("amount",p.amount()).param("quota",p.storeQuota()).update();
  }else{jdbc.sql("UPDATE pf_sale_product SET name=:name,description=:description,billing_mode=:mode,duration_days=:days,amount=:amount,store_quota=:quota,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
   .param("name",p.name().trim()).param("description",p.description().trim()).param("mode",p.billingMode()).param("days",p.durationDays()).param("amount",p.amount()).param("quota",p.storeQuota()).param("id",id).update();}
  if(jdbc.sql("SELECT COUNT(*) FROM pf_module_price WHERE module_id=:module AND billing_mode=:mode AND duration_days=:days").param("module",module).param("mode",p.billingMode()).param("days",p.durationDays()).query(Long.class).single()>0){
   jdbc.sql("UPDATE pf_module_price SET amount=:amount,store_quota=:quota,sale_status='ON_SALE' WHERE module_id=:module AND billing_mode=:mode AND duration_days=:days").param("amount",p.amount()).param("quota",p.storeQuota()).param("module",module).param("mode",p.billingMode()).param("days",p.durationDays()).update();
  }else{jdbc.sql("INSERT INTO pf_module_price(module_id,billing_mode,duration_days,amount,currency,store_quota,sale_status) VALUES(:module,:mode,:days,:amount,'CNY',:quota,'ON_SALE')").param("module",module).param("mode",p.billingMode()).param("days",p.durationDays()).param("amount",p.amount()).param("quota",p.storeQuota()).update();}
  jdbc.sql("UPDATE pf_module_definition SET sale_status='ON_SALE',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id").param("id",module).update();
  record(a,"SALE_PRODUCT_SAVED",p.moduleCode(),t);return ok(null,t);
 }
 @GetMapping("/cards") @PreAuthorize("hasAuthority('platform.module.view')")
 public Object cards(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){return ok(jdbc.sql("""
 SELECT c.id,c.product_id AS productId,c.plan_id AS planId,c.image_url AS imageUrl,c.sort_order AS sortOrder,c.enabled,
 COALESCE(p.name,b.name) AS name,COALESCE(p.description,b.description) AS description,
 COALESCE(p.amount,b.amount) AS amount,CASE WHEN p.id IS NULL THEN b.sale_status WHEN m.sale_status='ON_SALE' THEN p.sale_status ELSE m.sale_status END AS saleStatus,
 m.code AS moduleCode,p.billing_mode AS billingMode,p.duration_days AS durationDays,p.store_quota AS storeQuota
 FROM pf_sale_card c LEFT JOIN pf_sale_product p ON p.id=c.product_id LEFT JOIN pf_plan b ON b.id=c.plan_id
 LEFT JOIN pf_module_definition m ON m.id=p.module_id ORDER BY c.sort_order,c.id
 """).query().listOfRows(),t);}
 public record Card(Long id,Long productId,Long planId,@NotNull @Size(max=2000) String imageUrl,@Min(0) int sortOrder,boolean enabled){}
 @PostMapping("/cards") @PreAuthorize("hasAuthority('platform.module.manage')") @Transactional
 public Object saveCard(@Valid @RequestBody Card c,@AuthenticationPrincipal PlatformPrincipal a,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){
  if((c.productId()==null)==(c.planId()==null))throw invalid("请选择单模块商品或套餐");
  String image=c.imageUrl().trim();if(!image.isEmpty()&&!(image.startsWith("https://")||(image.startsWith("/images/")&&!image.contains(".."))))throw invalid("图片须使用HTTPS或本站/images/地址");
  String target=c.productId()!=null?"product_id":"plan_id";Long targetId=c.productId()!=null?c.productId():c.planId();
  String table=c.productId()!=null?"pf_sale_product":"pf_plan";
  if(jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE id=:id").param("id",targetId).query(Long.class).single()!=1)throw invalid("选择的商品不存在");
  Long existing=jdbc.sql("SELECT id FROM pf_sale_card WHERE "+target+"=:id FOR UPDATE").param("id",targetId).query(Long.class).optional().orElse(null);
  if(c.id()!=null&&!Objects.equals(c.id(),existing))throw invalid("编辑卡片不能换绑商品，请新建卡片");
  if(existing==null)jdbc.sql("INSERT INTO pf_sale_card("+target+",image_url,sort_order,enabled) VALUES(:target,:image,:sort,:enabled)").param("target",targetId).param("image",image).param("sort",c.sortOrder()).param("enabled",c.enabled()).update();
  else jdbc.sql("UPDATE pf_sale_card SET image_url=:image,sort_order=:sort,enabled=:enabled WHERE id=:id").param("image",image).param("sort",c.sortOrder()).param("enabled",c.enabled()).param("id",existing).update();
  record(a,"SALE_CARD_SAVED",String.valueOf(targetId),t);return ok(null,t);
 }
 @GetMapping("/coupons") @PreAuthorize("hasAuthority('platform.order.view')")
 public Object coupons(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){return ok(jdbc.sql("SELECT id,code,name,type,coupon_value AS couponValue,minimum_amount AS minimumAmount,expires_at AS expiresAt,CASE WHEN status='AVAILABLE' AND expires_at<CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE status END AS status FROM pf_sale_coupon ORDER BY id DESC").query().listOfRows(),t);}
 public record Coupon(@NotBlank @Size(max=100) String name,@NotNull @Pattern(regexp="DISCOUNT|DEDUCTION") String type,@NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal value,@NotNull @DecimalMin("0") BigDecimal minimumAmount,@NotNull @Future Instant expiresAt){}
 @PostMapping("/coupons") @PreAuthorize("hasAuthority('platform.order.manage')") @Transactional
 public Object coupon(@Valid @RequestBody Coupon c,@AuthenticationPrincipal PlatformPrincipal a,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){if(c.type().equals("DISCOUNT")&&c.value().compareTo(new BigDecimal("100"))>=0)throw invalid("折扣须大于0且小于100，例如80代表8折");
  String code=UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
  jdbc.sql("INSERT INTO pf_sale_coupon(code,name,type,coupon_value,minimum_amount,expires_at) VALUES(:code,:name,:type,:value,:minimum,:expiry)").param("code",code).param("name",c.name().trim()).param("type",c.type()).param("value",c.value()).param("minimum",c.minimumAmount()).param("expiry",Timestamp.from(c.expiresAt())).update();record(a,"COUPON_CREATED",code,t);return ok(Map.of("code",code),t);}
 @DeleteMapping("/coupons/{id}") @PreAuthorize("hasAuthority('platform.order.manage')") @Transactional
 public Object deleteCoupon(@PathVariable long id,@AuthenticationPrincipal PlatformPrincipal a,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){int n=jdbc.sql("UPDATE pf_sale_coupon SET status='VOID' WHERE id=:id AND status='AVAILABLE'").param("id",id).update();if(n!=1)throw invalid("券不存在或已被占用/使用/作废");record(a,"COUPON_VOIDED",String.valueOf(id),t);return ok(null,t);}
 public record Quote(Long productId,String planCode,String couponCode){}
 @PostMapping("/quote") @PreAuthorize("hasAuthority('platform.order.view')")
 public Object quote(@RequestBody Quote q,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){
  if((q.productId()==null)==(q.planCode()==null||q.planCode().isBlank()))throw invalid("请选择一种商品");
  BigDecimal base=q.productId()!=null?jdbc.sql("SELECT amount FROM pf_sale_product WHERE id=:id AND sale_status='ON_SALE'").param("id",q.productId()).query(BigDecimal.class).optional().orElseThrow(()->invalid("商品未在售")):jdbc.sql("SELECT amount FROM pf_plan WHERE code=:code AND sale_status='ON_SALE'").param("code",q.planCode()).query(BigDecimal.class).optional().orElseThrow(()->invalid("套餐未在售"));
  BigDecimal payable=base;
  if(q.couponCode()!=null&&!q.couponCode().isBlank()){
   var c=jdbc.sql("SELECT type,coupon_value,minimum_amount FROM pf_sale_coupon WHERE code=:code AND status='AVAILABLE' AND expires_at>CURRENT_TIMESTAMP").param("code",q.couponCode().trim()).query().listOfRows().stream().findFirst().orElseThrow(()->invalid("券码不可用或已过期"));
   if(base.compareTo((BigDecimal)c.get("minimum_amount"))<0)throw invalid("未达到优惠券最低消费");
   BigDecimal value=(BigDecimal)c.get("coupon_value");payable=c.get("type").equals("DISCOUNT")?base.multiply(value).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP):base.subtract(value).max(BigDecimal.ZERO);
  }
  return ok(Map.of("originalAmount",base,"discountAmount",base.subtract(payable),"payableAmount",payable,"currency","CNY"),t);
 }
}
