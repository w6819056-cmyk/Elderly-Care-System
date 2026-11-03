package com.smartelder.api.tenant.portal;
import com.smartelder.core.iam.TenantIamService;
import com.smartelder.core.api.access.TenantAccessContext;
import com.smartelder.api.tenant.auth.TenantStaffAuthService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class StorePortalService {
 private final JdbcClient jdbc;private final PasswordEncoder encoder;private final TenantIamService iam;private final TenantStaffAuthService auth;
 private final Map<String,java.util.ArrayDeque<Long>> limits=new HashMap<>();
 public StorePortalService(JdbcClient jdbc,PasswordEncoder encoder,TenantIamService iam,TenantStaffAuthService auth){this.jdbc=jdbc;this.encoder=encoder;this.iam=iam;this.auth=auth;}
 public synchronized void throttle(String key){long now=System.currentTimeMillis();var q=limits.computeIfAbsent(key,k->new java.util.ArrayDeque<>());while(!q.isEmpty()&&q.peekFirst()<now-600000)q.removeFirst();if(q.size()>=10)throw new IllegalArgumentException("请求过于频繁，请稍后重试");q.addLast(now);if(limits.size()>10000)limits.entrySet().removeIf(e->e.getValue().isEmpty()||e.getValue().peekLast()<now-600000);}
 private StoreRow store(String no){return jdbc.sql("SELECT id,public_id,store_no,name,status FROM core_store WHERE store_no=:no").param("no",no).query((rs,n)->new StoreRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5))).optional().orElseThrow(()->new IllegalArgumentException("门店不存在或暂未开放"));}
 private StoreRow active(String no){StoreRow s=store(no);if(!"ACTIVE".equals(s.status()))throw new IllegalArgumentException("门店暂未开放");return s;}
 public PublicStore page(String no){StoreRow s=active(no);String enterprise=jdbc.sql("SELECT name FROM core_enterprise ORDER BY id LIMIT 1").query(String.class).optional().orElse("企业管理系统");return new PublicStore(s.storeNo(),s.name(),enterprise);}
 public TenantStaffAuthService.LoginResult login(String no,String account,String password){return auth.loginForStore(account,password,active(no).id());}
 @Transactional public String apply(String no,String username,String password,String name,String phone,String email){StoreRow store=active(no);String id=UUID.randomUUID().toString();jdbc.sql("INSERT INTO iam_staff_application(public_id,store_id,username,password_hash,display_name,phone,email,status,created_at) VALUES(:id,:store,:user,:hash,:name,:phone,:email,'PENDING',:now)").param("id",id).param("store",store.id()).param("user",username.trim()).param("hash",encoder.encode(password)).param("name",name.trim()).param("phone",phone.trim()).param("email",email==null||email.isBlank()?null:email.trim()).param("now",Timestamp.from(Instant.now())).update();return id;}
 private void require(TenantAccessContext actor,String permission){if(!"HQ".equals(actor.staffType())||!actor.permissionCodes().contains(permission))throw new IllegalStateException("PERMISSION_DENIED");}
 private boolean inScope(TenantAccessContext actor,long store){return "ALL".equals(actor.dataScope().type())||actor.dataScope().storeIds().contains(store);}
 public List<ApplicationView> list(TenantAccessContext actor){require(actor,"iam.staff.view");return jdbc.sql("SELECT a.public_id,a.store_id,s.name,s.store_no,a.username,a.display_name,a.phone,a.email,a.status,a.review_note,a.created_at FROM iam_staff_application a JOIN core_store s ON s.id=a.store_id ORDER BY a.created_at DESC").query((rs,n)->new ApplicationView(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getTimestamp(11).toInstant())).list().stream().filter(a->inScope(actor,a.storeId())).toList();}
 @Transactional public void review(String id,boolean approve,Set<String> roles,String note,TenantAccessContext actor,String trace){require(actor,"iam.staff.manage");var a=jdbc.sql("SELECT store_id,username,password_hash,display_name,phone,email,status FROM iam_staff_application WHERE public_id=:id FOR UPDATE").param("id",id).query((rs,n)->new ReviewRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7))).optional().orElseThrow(()->new IllegalStateException("申请不存在"));if(!inScope(actor,a.storeId()))throw new IllegalStateException("DATA_SCOPE_DENIED");if(!"PENDING".equals(a.status()))throw new IllegalStateException("申请已处理，请刷新");String created=null;
 if(approve){if(roles==null||roles.isEmpty())throw new IllegalArgumentException("请选择至少一个门店角色");String storeId=jdbc.sql("SELECT public_id FROM core_store WHERE id=:id AND status='ACTIVE' FOR UPDATE").param("id",a.storeId()).query(String.class).optional().orElseThrow(()->new IllegalStateException("门店不是营业状态"));var staff=iam.createStaff(new TenantIamService.CreateStaffCommand(a.username(),a.hash(),a.name(),a.phone(),"STORE",storeId,roles),actor,trace);created=staff.publicId();jdbc.sql("UPDATE iam_staff SET email=:email WHERE public_id=:id").param("email",a.email()).param("id",created).update();}
 jdbc.sql("UPDATE iam_staff_application SET status=:status,password_hash=NULL,review_note=:note,created_staff_public_id=:staff,reviewed_at=:now WHERE public_id=:id").param("status",approve?"APPROVED":"REJECTED").param("note",note).param("staff",created).param("now",Timestamp.from(Instant.now())).param("id",id).update();
 jdbc.sql("INSERT INTO audit_tenant_log(actor_staff_id,action,target_type,target_public_id,trace_id,details_json,created_at) VALUES(:actor,:action,'STAFF_APPLICATION',:id,:trace,:summary,:now)").param("actor",actor.staffId()).param("action",approve?"APPLICATION_APPROVED":"APPLICATION_REJECTED").param("id",id).param("trace",trace).param("summary","{}").param("now",Timestamp.from(Instant.now())).update();}
 private record StoreRow(long id,String publicId,String storeNo,String name,String status){}
 private record ReviewRow(long storeId,String username,String hash,String name,String phone,String email,String status){}
 public record PublicStore(String storeNo,String name,String enterpriseName){}
 public record ApplicationView(String publicId,long storeId,String storeName,String storeNo,String username,String displayName,String phone,String email,String status,String reviewNote,Instant createdAt){}
}
