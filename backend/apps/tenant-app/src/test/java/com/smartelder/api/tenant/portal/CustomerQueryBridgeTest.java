package com.smartelder.api.tenant.portal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CustomerQueryBridgeTest {
 @Test void exposesOnlySummariesWithLiteralBoundedPrefixSearch(){
  var jdbc=JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:customer_api_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  jdbc.sql("CREATE TABLE customer_account(public_id VARCHAR(36),username VARCHAR(80),display_name VARCHAR(80),password_hash VARCHAR(100))").update();
  String id=UUID.randomUUID().toString();jdbc.sql("INSERT INTO customer_account VALUES(:id,'alice','客户甲','private-hash')").param("id",id).update();
  jdbc.sql("INSERT INTO customer_account VALUES(:id,'alicia','客户乙','other-private-hash')").param("id",UUID.randomUUID().toString()).update();
  var api=new CustomerQueryBridge(jdbc);assertThat(api.find(id).orElseThrow().username()).isEqualTo("alice");
  assertThat(api.find("not-an-id")).isEmpty();assertThat(api.search("al",1)).hasSize(1);assertThat(api.search("%_",50)).isEmpty();assertThat(api.search("",50)).isEmpty();
  assertThat(api.search("客户",50)).hasSize(2);
 }
}
