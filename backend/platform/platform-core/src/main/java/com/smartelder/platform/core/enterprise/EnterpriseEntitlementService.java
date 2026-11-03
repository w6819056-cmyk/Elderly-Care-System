package com.smartelder.platform.core.enterprise;

import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.commerce.LicenseIssueService;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.common.PlatformClock;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class EnterpriseEntitlementService {
    private final JdbcClient jdbc;
    private final PlatformClock clock;
    private final PlatformAuditService audit;
    private final LicenseIssueService licenses;
    private final TransactionTemplate tx;

    public EnterpriseEntitlementService(JdbcClient jdbc, PlatformClock clock, PlatformAuditService audit,
                                        LicenseIssueService licenses, PlatformTransactionManager manager) {
        this.jdbc=Objects.requireNonNull(jdbc);this.clock=Objects.requireNonNull(clock);this.audit=Objects.requireNonNull(audit);
        this.licenses=Objects.requireNonNull(licenses);this.tx=new TransactionTemplate(Objects.requireNonNull(manager));
    }

    public List<EntitlementView> list(String enterprisePublicId) {
        long enterpriseId=enterpriseId(enterprisePublicId);
        Instant now=clock.now();
        return jdbc.sql("""
                SELECT e.id,m.code,m.name,e.license_type,e.status,e.starts_at,e.expires_at,e.perpetual,e.store_quota,e.source_order_id
                FROM pf_entitlement e JOIN pf_module_definition m ON m.id=e.module_id
                WHERE e.enterprise_id=:enterpriseId ORDER BY m.menu_order,m.id
                """).param("enterpriseId",enterpriseId).query((r,n)->{
            Timestamp expiry=r.getTimestamp("expires_at");
            String status=r.getString("status");boolean perpetual=r.getBoolean("perpetual");
            if(!perpetual && expiry!=null && expiry.toInstant().isBefore(now) && "ACTIVE".equals(status))status="EXPIRED";
            return new EntitlementView(r.getLong("id"),r.getString("code"),r.getString("name"),r.getString("license_type"),status,
                    r.getTimestamp("starts_at").toInstant(),expiry==null?null:expiry.toInstant(),perpetual,r.getInt("store_quota"),r.getObject("source_order_id")!=null);
        }).list();
    }

    public EntitlementView update(String enterprisePublicId,long entitlementId,UpdateCommand command,PlatformPrincipal actor,String traceId) {
        return tx.execute(status->{
            long enterpriseId=enterpriseId(enterprisePublicId);
            var current=jdbc.sql("SELECT e.id,e.module_id,e.status,e.perpetual,m.code FROM pf_entitlement e JOIN pf_module_definition m ON m.id=e.module_id WHERE e.id=:id AND e.enterprise_id=:enterpriseId FOR UPDATE")
                    .param("id",entitlementId).param("enterpriseId",enterpriseId).query((r,n)->new Current(r.getLong(1),r.getLong(2),r.getString(3),r.getBoolean(4),r.getString(5))).optional()
                    .orElseThrow(()->new PlatformApiException(ErrorCode.NOT_FOUND,"企业模块授权不存在"));
            if(command.storeQuota()<1)throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"门店名额必须大于0");
            if(command.status()!=null && !List.of("ACTIVE","SUSPENDED").contains(command.status()))throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"授权状态无效");
            if(current.perpetual() && command.expiresAt()!=null)throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"永久授权不能设置到期时间");
            if(!current.perpetual() && command.expiresAt()==null)throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"限期授权必须设置到期时间");
            if(command.expiresAt()!=null && !command.expiresAt().isAfter(clock.now()))throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"到期时间必须晚于当前时间");
            String newStatus=command.status()==null?current.status():command.status();
            jdbc.sql("UPDATE pf_entitlement SET status=:status,expires_at=:expiresAt,store_quota=:quota,updated_at=:now,version=version+1 WHERE id=:id")
                    .param("status",newStatus).param("expiresAt",command.expiresAt()==null?null:Timestamp.from(command.expiresAt()), Types.TIMESTAMP)
                    .param("quota",command.storeQuota()).param("now",Timestamp.from(clock.now())).param("id",entitlementId).update();
            audit.record(actor.userId(),actor.publicId(),"PLATFORM_ENTERPRISE_MODULE_UPDATED","ENTERPRISE_MODULE",enterprisePublicId,
                    "{\"moduleCode\":\""+current.code()+"\",\"status\":\""+newStatus+"\",\"storeQuota\":"+command.storeQuota()+"}",traceId);
            jdbc.sql("SELECT id FROM pf_deployment_instance WHERE enterprise_id=:enterpriseId ORDER BY id").param("enterpriseId",enterpriseId)
                    .query(Long.class).list().forEach(id->licenses.issueForInstance(id,traceId));
            return list(enterprisePublicId).stream().filter(item->item.id()==entitlementId).findFirst().orElseThrow();
        });
    }

    private long enterpriseId(String publicId){return jdbc.sql("SELECT id FROM pf_enterprise WHERE public_id=:id").param("id",publicId).query(Long.class).optional().orElseThrow(()->new PlatformApiException(ErrorCode.NOT_FOUND,"企业不存在"));}
    private record Current(long id,long moduleId,String status,boolean perpetual,String code){}
    public record UpdateCommand(Instant expiresAt,int storeQuota,String status){}
    public record EntitlementView(long id,String moduleCode,String moduleName,String licenseType,String status,Instant startsAt,Instant expiresAt,boolean perpetual,int storeQuota,boolean orderBacked){}
}
