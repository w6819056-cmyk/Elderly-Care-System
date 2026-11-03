package com.smartelder.api.tenant.commerce;
import com.fasterxml.jackson.databind.*;
import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.api.tenant.licensing.*;
import com.smartelder.config.TenantLicenseProperties;
import com.smartelder.core.api.access.TenantAccessContext;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class TenantCommerceService {
 @org.springframework.beans.factory.annotation.Value("${app.commerce.image-base-url:}")
 private String imageBaseUrl="";
 private final ObjectProvider<PlatformLicenseClient> clients;private final ObjectProvider<LicenseSyncCoordinator> syncs;private final TenantLicenseProperties properties;private final ObjectMapper mapper;private final JdbcClient jdbc;private final Clock clock;
 public TenantCommerceService(ObjectProvider<PlatformLicenseClient> c,ObjectProvider<LicenseSyncCoordinator> s,TenantLicenseProperties p,ObjectMapper m,JdbcClient j,Clock clock){clients=c;syncs=s;properties=p;mapper=m;jdbc=j;this.clock=clock;}
 private void permission(TenantAccessContext a,boolean write){if(!"HQ".equals(a.staffType())||!a.permissionCodes().contains(write?"core.module.purchase":"core.module.view"))throw new TenantStaffAuthException(403,"PERMISSION_DENIED",write?"仅有购买模块权限的总部员工可下单和支付":"仅有模块查看权限的总部员工可访问模块购买");}
 public Object configuration(TenantAccessContext a){permission(a,false);return Map.of("instanceConfigured",configured(),"canPurchase",a.permissionCodes().contains("core.module.purchase"),"imageBaseUrl",imageBaseUrl);}
 private boolean configured(){return properties.isSyncConfigured()&&clients.getIfAvailable()!=null;}
 public JsonNode call(String method,String suffix,Object body,TenantAccessContext a,String trace){
  boolean write=!method.equals("GET")&&!suffix.equals("/quote");permission(a,write);
  if(!configured())throw new ResponseStatusException(HttpStatus.CONFLICT,"企业实例尚未配置平台连接与激活，请先完成实例激活后购买");
  try{
   JsonNode response=clients.getIfAvailable().commerce(method,suffix,body);
   if(response==null||!"OK".equals(response.path("code").asText())||!response.has("data"))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"平台购买服务返回无效");
   if(write)jdbc.sql("INSERT INTO audit_tenant_log(actor_staff_id,action,target_type,target_public_id,trace_id,details_json,created_at) VALUES(:staff,:action,'PLATFORM_ORDER',:target,:trace,'{}',:now)")
    .param("staff",a.staffId()).param("action",suffix.endsWith("/cancel")?"MODULE_ORDER_CANCELLED":suffix.endsWith("/pay-simulated")?"MODULE_ORDER_MOCK_PAID":"MODULE_ORDER_CREATED").param("target",response.path("data").path("publicId").asText("platform-order")).param("trace",trace).param("now",Timestamp.from(clock.instant())).update();
   return response.path("data");
  }catch(RestClientResponseException e){String message="平台购买服务拒绝请求";try{message=mapper.readTree(e.getResponseBodyAsString()).path("message").asText(message);}catch(Exception ignored){}throw new ResponseStatusException(e.getStatusCode(),message);}
   catch(ResourceAccessException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"暂时无法连接平台，原有授权与订单保留，请稍后刷新确认结果");}
 }
 public Object sync(TenantAccessContext a){permission(a,true);var sync=syncs.getIfAvailable();if(sync==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"尚未启用许可证同步，请检查实例部署配置");try{sync.syncNow();return Map.of("synced",true);}catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"授权同步暂未完成，购买订单已保留，请稍后重试");}}
}
