package com.smartelder.api.tenant.portal;
import com.smartelder.core.api.access.TenantAccessContext;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class VisitBookingService {
 private final JdbcClient jdbc;
 public VisitBookingService(JdbcClient jdbc){this.jdbc=jdbc;}
 @Transactional public String create(String no,String name,String phone,String type,LocalDate date,String remark){
  LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));if(date.isBefore(today)||date.isAfter(today.plusDays(90)))throw new IllegalArgumentException("请选择今天起 90 天内的日期");if(!Set.of("CONSULTATION","VISIT").contains(type))throw new IllegalArgumentException("预约类型无效");
  long store=jdbc.sql("SELECT id FROM core_store WHERE store_no=:no AND status='ACTIVE'").param("no",no).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("门店暂未开放"));String id=UUID.randomUUID().toString();var now=Timestamp.from(Instant.now());
  jdbc.sql("INSERT INTO core_visit_booking(public_id,store_id,visitor_name,phone,visit_type,preferred_date,remark,status,created_at,updated_at) VALUES(:id,:store,:name,:phone,:type,:date,:remark,'PENDING',:now,:now)").param("id",id).param("store",store).param("name",name.trim()).param("phone",phone.trim()).param("type",type).param("date",java.sql.Date.valueOf(date)).param("remark",remark).param("now",now).update();return id;
 }
 private void permission(TenantAccessContext actor,String code){if(!actor.permissionCodes().contains(code))throw new com.smartelder.api.tenant.auth.TenantStaffAuthException(403,"PERMISSION_DENIED","无权处理预约");}
 private boolean scope(TenantAccessContext actor,long store){return "ALL".equals(actor.dataScope().type())||actor.dataScope().storeIds().contains(store);}
 public List<Booking> list(TenantAccessContext actor){permission(actor,"core.visit.view");return jdbc.sql("SELECT b.public_id,b.store_id,s.name,b.visitor_name,b.phone,b.visit_type,b.preferred_date,b.remark,b.status,b.created_at FROM core_visit_booking b JOIN core_store s ON s.id=b.store_id ORDER BY b.created_at DESC").query((rs,n)->new Booking(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getDate(7).toLocalDate(),rs.getString(8),rs.getString(9),rs.getTimestamp(10).toInstant())).list().stream().filter(b->scope(actor,b.storeId())).toList();}
 @Transactional public void status(String id,String target,TenantAccessContext actor,String trace){permission(actor,"core.visit.manage");var current=jdbc.sql("SELECT store_id,status FROM core_visit_booking WHERE public_id=:id FOR UPDATE").param("id",id).query((rs,n)->new State(rs.getLong(1),rs.getString(2))).optional().orElseThrow(()->new IllegalStateException("预约不存在"));if(!scope(actor,current.store()))throw new com.smartelder.api.tenant.auth.TenantStaffAuthException(403,"DATA_SCOPE_DENIED","预约不在管理范围内");String storeStatus=jdbc.sql("SELECT status FROM core_store WHERE id=:id").param("id",current.store()).query(String.class).single();if(!"ACTIVE".equals(storeStatus))throw new IllegalStateException("门店不是营业状态");var allowed=Map.of("PENDING",Set.of("CONFIRMED","CANCELLED"),"CONFIRMED",Set.of("COMPLETED","CANCELLED"));if(!allowed.getOrDefault(current.status(),Set.of()).contains(target))throw new IllegalStateException("预约状态已改变或不允许该操作，请刷新");var now=Timestamp.from(Instant.now());jdbc.sql("UPDATE core_visit_booking SET status=:status,updated_at=:now WHERE public_id=:id").param("status",target).param("now",now).param("id",id).update();jdbc.sql("INSERT INTO audit_tenant_log(actor_staff_id,action,target_type,target_public_id,trace_id,details_json,created_at) VALUES(:actor,'VISIT_STATUS_CHANGED','VISIT_BOOKING',:id,:trace,:details,:now)").param("actor",actor.staffId()).param("id",id).param("trace",trace).param("details","{\"status\":\""+target+"\"}").param("now",now).update();}
 private record State(long store,String status){}
 public record Booking(String publicId,long storeId,String storeName,String visitorName,String phone,String visitType,LocalDate preferredDate,String remark,String status,Instant createdAt){}
}
