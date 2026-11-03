package com.smartelder.api.tenant.portal;
import com.smartelder.core.api.customer.CustomerQueryApi;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.*;

@Component
public class CustomerQueryBridge implements CustomerQueryApi {
 private final JdbcClient jdbc;public CustomerQueryBridge(JdbcClient jdbc){this.jdbc=jdbc;}
 public Optional<CustomerSummary> find(String publicId){
  if(publicId==null||!publicId.matches("[a-fA-F0-9-]{36}"))return Optional.empty();
  return jdbc.sql("SELECT public_id,username,display_name FROM customer_account WHERE public_id=:id").param("id",publicId).query((r,n)->new CustomerSummary(r.getString(1),r.getString(2),r.getString(3))).optional();
 }
 public List<CustomerSummary> search(String query,int limit){
  String q=query==null?"":query.trim();if(q.length()<2||q.length()>80)return List.of();
  int size=Math.max(1,Math.min(limit,50));
  // Literal prefix matching avoids exposing every customer through SQL wildcard input.
  return jdbc.sql("SELECT public_id,username,display_name FROM customer_account WHERE public_id=:q OR LEFT(username,:length)=:q OR LEFT(display_name,:length)=:q ORDER BY username LIMIT :limit").param("q",q).param("length",q.length()).param("limit",size).query((r,n)->new CustomerSummary(r.getString(1),r.getString(2),r.getString(3))).list();
 }
}
