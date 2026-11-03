package com.smartelder.platform.api.enterprise;
import com.smartelder.common.*;
import com.smartelder.platform.core.common.*;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.enterprise.EnterpriseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/platform/v1/enterprise-applications")
public class EnterpriseApplicationController {
 private final JdbcClient jdbc;private final EnterpriseService enterprises;private final PlatformAuditService audit;private final Clock clock;
 public EnterpriseApplicationController(JdbcClient j,EnterpriseService e,PlatformAuditService a,Clock c){jdbc=j;enterprises=e;audit=a;clock=c;}
 public record Application(@NotBlank @Size(max=40) String orgNo,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=100) String contactName,@NotBlank @Size(max=40) String contactPhone){}
 @PostMapping @Transactional public Object submit(@Valid @RequestBody Application b,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){
  String org=b.orgNo().trim();if(!org.matches("[a-zA-Z0-9_-]{2,40}"))throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"企业编号须为2至40位字母、数字、下划线或连字符");
  if(jdbc.sql("SELECT COUNT(*) FROM pf_enterprise WHERE org_no=:org").param("org",org).query(Long.class).single()>0)throw new PlatformApiException(ErrorCode.CONFLICT,"该企业已经登记");
  var existing=jdbc.sql("SELECT public_id,status FROM pf_enterprise_application WHERE org_no=:org FOR UPDATE").param("org",org).query().listOfRows().stream().findFirst();
  String id=existing.map(x->String.valueOf(x.get("public_id"))).orElseGet(()->PublicIds.newId("app"));
  if(existing.isPresent())throw new PlatformApiException(ErrorCode.CONFLICT,"该企业已提交登记，请联系平台处理已有申请");
  jdbc.sql("INSERT INTO pf_enterprise_application(public_id,org_no,name,contact_name,contact_phone) VALUES(:id,:org,:name,:contact,:phone)").param("id",id).param("org",org).param("name",b.name().trim()).param("contact",b.contactName().trim()).param("phone",b.contactPhone().trim()).update();
  return ApiResponse.success(java.util.Map.of("applicationId",id,"status","PENDING"),t,clock);
 }
 @GetMapping @PreAuthorize("hasAuthority('platform.enterprise.view')") public Object list(@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){return ApiResponse.success(jdbc.sql("SELECT public_id AS publicId,org_no AS orgNo,name,contact_name AS contactName,contact_phone AS contactPhone,status,reason,created_at AS createdAt FROM pf_enterprise_application ORDER BY id DESC LIMIT 200").query().listOfRows(),t,clock);}
 public record Review(boolean approved,@NotBlank @Size(max=500) String reason){}
 @PostMapping("/{id}/review") @PreAuthorize("hasAuthority('platform.enterprise.manage')") @Transactional
 public Object review(@PathVariable String id,@Valid @RequestBody Review b,@AuthenticationPrincipal PlatformPrincipal actor,@RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String t){
  var row=jdbc.sql("SELECT * FROM pf_enterprise_application WHERE public_id=:id FOR UPDATE").param("id",id).query().listOfRows().stream().findFirst().orElseThrow(()->new PlatformApiException(ErrorCode.NOT_FOUND,"登记申请不存在"));
  if(!row.get("status").equals("PENDING"))throw new PlatformApiException(ErrorCode.CONFLICT,"申请已处理");
  String enterprise=null;if(b.approved()){var result=enterprises.create(new EnterpriseService.CreateEnterpriseCommand((String)row.get("name"),(String)row.get("org_no"),(String)row.get("contact_name"),(String)row.get("contact_phone")),actor,t);enterprise=result.publicId();}
  jdbc.sql("UPDATE pf_enterprise_application SET status=:status,reason=:reason,enterprise_public_id=:enterprise,updated_at=CURRENT_TIMESTAMP WHERE public_id=:id").param("status",b.approved()?"APPROVED":"REJECTED").param("reason",b.reason().trim()).param("enterprise",enterprise,java.sql.Types.VARCHAR).param("id",id).update();
  audit.record(actor.userId(),actor.publicId(),b.approved()?"ENTERPRISE_APPLICATION_APPROVED":"ENTERPRISE_APPLICATION_REJECTED","ENTERPRISE_APPLICATION",id,null,t);
  return ApiResponse.success(null,t,clock);
 }
}
