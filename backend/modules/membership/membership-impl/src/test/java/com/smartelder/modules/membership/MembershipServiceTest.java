package com.smartelder.modules.membership;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartelder.core.api.access.*;
import com.smartelder.core.api.customer.CustomerQueryApi;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class MembershipServiceTest {
  MembershipService s;
  JdbcTemplate db;
  StaffAccessApi a;
  CustomerQueryApi c;
  String customer = UUID.randomUUID().toString();

  @BeforeEach
  void init() throws Exception {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    db = new JdbcTemplate(ds);
    for (String sql : Files.readString(Path.of("payload/migrations/V1__membership.sql")).split(";"))
      if (!sql.isBlank()) db.execute(sql);
    a = mock(StaffAccessApi.class);
    c = mock(CustomerQueryApi.class);
    when(a.current())
        .thenReturn(
            new TenantAccessContext(
                1,
                UUID.randomUUID().toString(),
                "HQ",
                null,
                Set.of(),
                Set.of(),
                new DataScope("ALL", Set.of())));
    when(c.find(customer))
        .thenReturn(Optional.of(new CustomerQueryApi.CustomerSummary(customer, "u", "测试")));
    s =
        new MembershipService(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), c, a);
  }

  Map<String, Object> level(String name) {
    return s.saveLevel(null, name, 1, "权益", true, null);
  }

  @Test
  void lifecycleAndOptimisticConflict() {
    var l = level("普通");
    var m = s.enroll(customer, (String) l.get("id"), "开通");
    assertThatThrownBy(() -> s.enroll(customer, (String) l.get("id"), "重复"))
        .hasMessageContaining("409");
    var l2 = level("高级");
    s.change((String) m.get("id"), (String) l2.get("id"), "升级", 0L);
    assertThatThrownBy(() -> s.change((String) m.get("id"), (String) l.get("id"), "过期", 0L))
        .hasMessageContaining("409");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM mem_change", Integer.class)).isEqualTo(2);
    assertThatThrownBy(() -> s.saveLevel((String) l.get("id"), "更名", 1, "", true, 8L))
        .hasMessageContaining("409");
  }

  @Test
  void disabledAndOrdinaryCustomer() {
    var l = level("普通");
    assertThat(((Map<?, ?>) s.card(customer)).get("member")).isEqualTo(false);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM mem_member", Integer.class)).isZero();
    s.saveLevel((String) l.get("id"), "普通", 1, "", false, 0L);
    assertThatThrownBy(() -> s.enroll(customer, (String) l.get("id"), "开通"))
        .hasMessageContaining("400");
  }

  @Test
  void storeCannotManage() {
    when(a.current())
        .thenReturn(
            new TenantAccessContext(
                2,
                UUID.randomUUID().toString(),
                "STORE",
                1L,
                Set.of(),
                Set.of(),
                new DataScope("STORES", Set.of(1L))));
    assertThatThrownBy(() -> s.levels()).hasMessageContaining("403");
    assertThatThrownBy(() -> s.members("")).hasMessageContaining("403");
    assertThatThrownBy(() -> s.history(UUID.randomUUID().toString())).hasMessageContaining("403");
    assertThatThrownBy(() -> s.searchCustomers("")).hasMessageContaining("403");
    assertThat((Map<?, ?>) s.lookup(customer)).hasSize(2);
  }

  @Test
  void concurrentChangeWritesOneAudit() throws Exception {
    var first = level("初始");
    var next = level("目标");
    var m = s.enroll(customer, (String) first.get("id"), "开通");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var gate = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> work =
          () -> {
            gate.await();
            try {
              s.change((String) m.get("id"), (String) next.get("id"), "并发", 0L);
              return true;
            } catch (org.springframework.web.server.ResponseStatusException e) {
              if (e.getStatusCode().value() != 409) throw e;
              return false;
            }
          };
      var a = pool.submit(work);
      var b = pool.submit(work);
      gate.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM mem_change", Integer.class)).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void nameAndDisabledCard() {
    var l = s.saveLevel(null, "  标准  ", 1, "权益", true, null);
    assertThat(l.get("name")).isEqualTo("标准");
    assertThatThrownBy(() -> level("标准")).hasMessageContaining("409");
    assertThatThrownBy(() -> s.saveLevel(null, "x", -1, "", true, null))
        .hasMessageContaining("400");
    s.enroll(customer, (String) l.get("id"), "开通");
    s.saveLevel((String) l.get("id"), "标准", 1, "权益", false, 0L);
    var card = (Map<?, ?>) s.card(customer);
    assertThat(card.get("status")).isEqualTo("LEVEL_DISABLED");
    assertThat(card.get("levelEnabled")).isEqualTo(false);
    assertThat(card).hasSize(8);
  }
}
