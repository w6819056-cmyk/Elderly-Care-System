package com.smartelder.api.tenant.portal;

import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import com.smartelder.core.api.access.TenantAccessContext;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerManagementService {
    private final JdbcClient jdbc;
    public CustomerManagementService(JdbcClient jdbc) { this.jdbc = jdbc; }

    private record Scope(String sql, Map<String,Object> params, boolean all) {}
    private Scope scope(TenantAccessContext actor) {
        if (!actor.permissionCodes().contains("core.customer.view"))
            throw new TenantStaffAuthException(403,"PERMISSION_DENIED","没有查看客户信息权限");
        boolean all="HQ".equals(actor.staffType()) && "ALL".equals(actor.dataScope().type());
        if(all) return new Scope("1=1",Map.of(),true);
        Set<Long> stores="STORE".equals(actor.staffType()) ? Set.of(actor.homeStoreId()) : actor.dataScope().storeIds();
        return stores.isEmpty() ? new Scope("1=0",Map.of(),false)
                : new Scope("b.store_id IN (:stores)",Map.of("stores",stores),false);
    }
    private String visible(Scope scope) {
        return scope.all() ? "1=1" : "EXISTS (SELECT 1 FROM core_visit_booking b WHERE b.customer_id=c.id AND "+scope.sql()+")";
    }
    public Page list(TenantAccessContext actor,String query,int page,int size) {
        Scope scope=scope(actor);
        String q=query==null?"":query.trim();
        if(q.length()>80 || page<1 || page>100000 || size<1 || size>50) throw new IllegalArgumentException("搜索或分页参数无效");
        Map<String,Object> params=new HashMap<>(scope.params());
        String where=visible(scope);
        if(!q.isEmpty()) {
            params.put("q","%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            where+=" AND (c.username LIKE :q ESCAPE '!' OR c.display_name LIKE :q ESCAPE '!' OR EXISTS (SELECT 1 FROM core_visit_booking b WHERE b.customer_id=c.id AND "+scope.sql()+" AND b.phone LIKE :q ESCAPE '!'))";
        }
        long total=jdbc.sql("SELECT COUNT(*) FROM customer_account c WHERE "+where).params(params).query(Long.class).single();
        params.put("limit",size); params.put("offset",(page-1)*size);
        List<Summary> items=jdbc.sql(summarySql(scope)+" WHERE "+where+" ORDER BY c.created_at DESC,c.id DESC LIMIT :limit OFFSET :offset")
                .params(params).query((r,n)->new Summary(r.getString("public_id"),r.getString("username"),r.getString("display_name"),r.getTimestamp("created_at").toInstant(),r.getLong("booking_count"),List.of())).list();
        return new Page(items.stream().map(row->withStores(row,scope)).toList(),total,page,size);
    }
    private String summarySql(Scope scope) {
        return "SELECT c.public_id,c.username,c.display_name,c.created_at,(SELECT COUNT(*) FROM core_visit_booking b WHERE b.customer_id=c.id AND "+scope.sql()+") AS booking_count FROM customer_account c";
    }
    private Summary withStores(Summary row,Scope scope) {
        Map<String,Object> params=new HashMap<>(scope.params());params.put("id",row.publicId());
        var stores=jdbc.sql("SELECT DISTINCT s.public_id,s.store_no,s.name FROM core_store s JOIN core_visit_booking b ON b.store_id=s.id JOIN customer_account c ON c.id=b.customer_id WHERE c.public_id=:id AND "+scope.sql()+" ORDER BY s.store_no,s.public_id")
                .params(params).query((r,n)->new Store(r.getString(1),r.getString(2),r.getString(3))).list();
        return new Summary(row.publicId(),row.username(),row.displayName(),row.createdAt(),row.bookingCount(),stores);
    }
    public Detail detail(TenantAccessContext actor,String id) {
        Scope scope=scope(actor);
        Map<String,Object> params=new HashMap<>(scope.params());params.put("id",id);
        Summary customer=jdbc.sql(summarySql(scope)+" WHERE c.public_id=:id AND "+visible(scope)).params(params)
                .query((r,n)->new Summary(r.getString("public_id"),r.getString("username"),r.getString("display_name"),r.getTimestamp("created_at").toInstant(),r.getLong("booking_count"),List.of()))
                .optional().orElseThrow(()->new TenantStaffAuthException(404,"CUSTOMER_NOT_FOUND","客户不存在或不在管理范围内"));
        var bookings=jdbc.sql("SELECT b.public_id,s.name,b.visitor_name,b.phone,b.visit_type,b.preferred_date,b.status,b.remark FROM core_visit_booking b JOIN core_store s ON s.id=b.store_id JOIN customer_account c ON c.id=b.customer_id WHERE c.public_id=:id AND "+scope.sql()+" ORDER BY b.created_at DESC,b.id DESC LIMIT 100")
                .params(params).query((r,n)->new Booking(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getDate(6).toLocalDate(),r.getString(7),r.getString(8))).list();
        return new Detail(withStores(customer,scope),bookings);
    }
    public void requireVisible(TenantAccessContext actor,String id) {
        Scope scope=scope(actor);
        if(!"HQ".equals(actor.staffType()))
            throw new TenantStaffAuthException(403,"PERMISSION_DENIED","仅企业总部可查看客户模块摘要");
        Map<String,Object> params=new HashMap<>(scope.params());params.put("id",id);
        long count=jdbc.sql("SELECT COUNT(*) FROM customer_account c WHERE c.public_id=:id AND "+visible(scope))
                .params(params).query(Long.class).single();
        if(count!=1)
            throw new TenantStaffAuthException(404,"CUSTOMER_NOT_FOUND","客户不存在或不在管理范围内");
    }
    public record Store(String publicId,String storeNo,String name) {}
    public record Summary(String publicId,String username,String displayName,Instant createdAt,long bookingCount,List<Store> stores) {}
    public record Page(List<Summary> items,long total,int page,int size) {}
    public record Booking(String publicId,String storeName,String visitorName,String phone,String visitType,LocalDate preferredDate,String status,String remark) {}
    public record Detail(Summary customer,List<Booking> bookings) {}
}
