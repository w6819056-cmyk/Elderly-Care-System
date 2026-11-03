package com.smartelder.api.tenant.portal;

import com.smartelder.api.tenant.auth.TenantStaffAuthException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
 private final JdbcClient jdbc; private final PasswordEncoder encoder; private final VisitBookingService visits;
 private final String dummy;
 public CustomerService(JdbcClient jdbc,PasswordEncoder encoder,VisitBookingService visits){this.jdbc=jdbc;this.encoder=encoder;this.visits=visits;dummy=encoder.encode(UUID.randomUUID().toString());}
 private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private TenantStaffAuthException unauthorized(){return new TenantStaffAuthException(401,"CUSTOMER_LOGIN_REQUIRED","请登录用户账号");}
 public long resolve(String token){if(token==null||token.length()>200)throw unauthorized();return jdbc.sql("SELECT customer_id FROM customer_session WHERE token_hash=:hash AND expires_at>:now").param("hash",hash(token)).param("now",Timestamp.from(Instant.now())).query(Long.class).optional().orElseThrow(this::unauthorized);}
 public Profile me(long id){return jdbc.sql("SELECT public_id,username,display_name FROM customer_account WHERE id=:id").param("id",id).query((r,n)->new Profile(r.getString(1),r.getString(2),r.getString(3))).single();}
 @Transactional public Login register(String username,String password,String name){
  String account=username.trim().toLowerCase(Locale.ROOT);
  if(jdbc.sql("SELECT COUNT(*) FROM customer_account WHERE username=:u").param("u",account).query(Long.class).single()>0)throw new IllegalStateException("账号已被使用");
  String id=UUID.randomUUID().toString();
  jdbc.sql("INSERT INTO customer_account(public_id,username,password_hash,display_name,created_at) VALUES(:id,:u,:p,:n,:now)").param("id",id).param("u",account).param("p",encoder.encode(password)).param("n",name.trim()).param("now",Timestamp.from(Instant.now())).update();
  long customer=jdbc.sql("SELECT id FROM customer_account WHERE public_id=:id").param("id",id).query(Long.class).single();return session(customer);
 }
 public Login login(String username,String password){
  var row=jdbc.sql("SELECT id,password_hash FROM customer_account WHERE username=:u").param("u",username.trim().toLowerCase(Locale.ROOT)).query((r,n)->new Account(r.getLong(1),r.getString(2))).optional();
  boolean valid=encoder.matches(password,row.map(Account::password).orElse(dummy));
  if(row.isEmpty()||!valid)throw new TenantStaffAuthException(401,"CUSTOMER_LOGIN_FAILED","账号或密码不正确");return session(row.get().id());
 }
 private Login session(long id){
  jdbc.sql("DELETE FROM customer_session WHERE expires_at<:now").param("now",Timestamp.from(Instant.now())).update();
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant expires=Instant.now().plus(Duration.ofDays(7));
  jdbc.sql("INSERT INTO customer_session(token_hash,customer_id,expires_at) VALUES(:h,:id,:expires)").param("h",hash(token)).param("id",id).param("expires",Timestamp.from(expires)).update();return new Login(token,expires,me(id));
 }
 public void logout(String token){resolve(token);jdbc.sql("DELETE FROM customer_session WHERE token_hash=:h").param("h",hash(token)).update();}
 @Transactional public String book(long customer,String no,VisitBookingController.Create b){String id=visits.create(no,b.visitorName(),b.phone(),b.visitType(),b.preferredDate(),b.remark());jdbc.sql("UPDATE core_visit_booking SET customer_id=:customer WHERE public_id=:id").param("customer",customer).param("id",id).update();return id;}
 public List<MyBooking> bookings(long customer){return jdbc.sql("SELECT b.public_id,s.store_no,s.name,b.visitor_name,b.phone,b.visit_type,b.preferred_date,b.status,b.remark FROM core_visit_booking b JOIN core_store s ON s.id=b.store_id WHERE b.customer_id=:id ORDER BY b.created_at DESC,b.id DESC").param("id",customer).query((r,n)->new MyBooking(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getDate(7).toLocalDate(),r.getString(8),r.getString(9))).list();}
 private record Account(long id,String password){}
 public record Profile(String publicId,String username,String displayName){}
 public record Login(String accessToken,Instant expiresAt,Profile profile){}
 public record MyBooking(String bookingNo,String storeNo,String storeName,String visitorName,String phone,String visitType,LocalDate preferredDate,String status,String remark){}
}
