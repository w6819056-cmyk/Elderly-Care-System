package com.smartelder.modules.membership;

import com.smartelder.core.api.access.StaffAccessApi;
import com.smartelder.core.api.customer.CustomerQueryApi;
import com.smartelder.module.api.CustomerFeatureProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class MembershipConfiguration {
  @Bean
  public MembershipService membershipService(
      JdbcTemplate db, PlatformTransactionManager tm, CustomerQueryApi c, StaffAccessApi a) {
    return new MembershipService(db, new TransactionTemplate(tm), c, a);
  }

  @Bean
  public MembershipController membershipController(MembershipService s) {
    return new MembershipController(s);
  }

  @Bean
  public CustomerFeatureProvider membershipFeature(MembershipService s) {
    return new CustomerFeatureProvider() {
      public String moduleCode() {
        return "membership";
      }

      public Object get(String id) {
        return s.card(id);
      }
    };
  }
}
