package com.smartelder.modules.membership;

import com.smartelder.core.api.access.StaffAccessApi;
import com.smartelder.core.api.customer.CustomerQueryApi;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class MembershipService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final CustomerQueryApi customers;
  final StaffAccessApi access;

  public MembershipService(
      JdbcTemplate db, TransactionTemplate tx, CustomerQueryApi customers, StaffAccessApi access) {
    this.db = db;
    this.tx = tx;
    this.customers = customers;
    this.access = access;
  }

  void hq() {
    if (access.current() == null || !"HQ".equals(access.current().staffType()))
      throw error(HttpStatus.FORBIDDEN, "仅总部可管理会员");
  }

  static ResponseStatusException error(HttpStatus s, String m) {
    return new ResponseStatusException(s, m);
  }

  static String uuid(String s) {
    try {
      return UUID.fromString(s).toString();
    } catch (Exception e) {
      throw error(HttpStatus.BAD_REQUEST, "无效UUID");
    }
  }

  static void text(String s, int max) {
    if (s == null || s.isBlank() || s.length() > max)
      throw error(HttpStatus.BAD_REQUEST, "文本为空或过长");
  }

  Map<String, Object> level(java.sql.ResultSet r) throws java.sql.SQLException {
    return Map.of(
        "id",
        r.getString("public_id"),
        "name",
        r.getString("name"),
        "rank",
        r.getInt("level_rank"),
        "benefits",
        r.getString("benefits"),
        "enabled",
        r.getBoolean("enabled"),
        "version",
        r.getLong("version"));
  }

  Map<String, Object> member(java.sql.ResultSet r) throws java.sql.SQLException {
    return Map.of(
        "id",
        r.getString("public_id"),
        "customerPublicId",
        r.getString("customer_public_id"),
        "levelId",
        r.getString("level_public_id"),
        "version",
        r.getLong("version"));
  }

  public List<Map<String, Object>> levels() {
    hq();
    return db.query("SELECT * FROM mem_level ORDER BY level_rank,id", (r, n) -> level(r));
  }

  public Map<String, Object> saveLevel(
      String id, String name, int rank, String benefits, boolean enabled, Long version) {
    hq();
    name = name == null ? null : name.trim();
    text(name, 100);
    if (rank < 0 || rank > 9999) throw error(HttpStatus.BAD_REQUEST, "等级排序范围0到9999");
    final String normalizedName = name;
    if (benefits == null || benefits.length() > 4000) throw error(HttpStatus.BAD_REQUEST, "权益过长");
    try {
      return tx.execute(
          t -> {
            String key = id == null ? UUID.randomUUID().toString() : uuid(id);
            if (id == null)
              db.update(
                  "INSERT INTO mem_level(public_id,name,level_rank,benefits,enabled,version) VALUES(?,?,?,?,?,0)",
                  key,
                  normalizedName,
                  rank,
                  benefits,
                  enabled);
            else if (version == null
                || db.update(
                        "UPDATE mem_level SET name=?,level_rank=?,benefits=?,enabled=?,version=version+1 WHERE public_id=? AND version=?",
                        normalizedName,
                        rank,
                        benefits,
                        enabled,
                        key,
                        version)
                    != 1) throw error(HttpStatus.CONFLICT, "等级版本冲突");
            return db.query("SELECT * FROM mem_level WHERE public_id=?", (r, n) -> level(r), key)
                .get(0);
          });
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw error(HttpStatus.CONFLICT, "等级名称重复");
    }
  }

  public Object searchCustomers(String q) {
    hq();
    return customers.search(q == null ? "" : q, 50);
  }

  public List<Map<String, Object>> members(String q) {
    hq();
    if (q == null || q.isBlank())
      return db.query("SELECT * FROM mem_member ORDER BY id DESC LIMIT 100", (r, n) -> member(r));
    var ids = customers.search(q, 100).stream().map(c -> c.publicId()).toList();
    if (ids.isEmpty()) return List.of();
    return db.query(
        "SELECT * FROM mem_member WHERE customer_public_id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ") ORDER BY id DESC LIMIT 100",
        (r, n) -> member(r),
        ids.toArray());
  }

  public Map<String, Object> enroll(String customer, String level, String reason) {
    hq();
    String c = uuid(customer);
    if (customers.find(c).isEmpty()) throw error(HttpStatus.NOT_FOUND, "客户不存在");
    text(reason, 1000);
    try {
      return tx.execute(
          t -> {
            active(level);
            String id = UUID.randomUUID().toString();
            db.update(
                "INSERT INTO mem_member(public_id,customer_public_id,level_public_id,version) VALUES(?,?,?,0)",
                id,
                c,
                uuid(level));
            audit(id, null, uuid(level), reason);
            return findMember(id);
          });
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw error(HttpStatus.CONFLICT, "客户已入会");
    }
  }

  void active(String id) {
    var rows =
        db.queryForList("SELECT enabled FROM mem_level WHERE public_id=? FOR UPDATE", uuid(id));
    if (rows.isEmpty()
        || !Boolean.TRUE.equals(rows.get(0).get("enabled"))
            && !Integer.valueOf(1).equals(rows.get(0).get("enabled")))
      throw error(HttpStatus.BAD_REQUEST, "等级不存在或已停用");
  }

  Map<String, Object> findMember(String id) {
    var rows =
        db.query("SELECT * FROM mem_member WHERE public_id=?", (r, n) -> member(r), uuid(id));
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "会员不存在");
    return rows.get(0);
  }

  public Map<String, Object> change(String id, String level, String reason, Long version) {
    hq();
    text(reason, 1000);
    return tx.execute(
        t -> {
          var rows =
              db.query(
                  "SELECT * FROM mem_member WHERE public_id=? FOR UPDATE",
                  (r, n) -> member(r),
                  uuid(id));
          if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "会员不存在");
          var old = rows.get(0);
          if (version == null || !version.equals(old.get("version")))
            throw error(HttpStatus.CONFLICT, "会员版本冲突");
          active(level);
          if (old.get("levelId").equals(uuid(level))) throw error(HttpStatus.CONFLICT, "会员已是该等级");
          db.update(
              "UPDATE mem_member SET level_public_id=?,version=version+1 WHERE public_id=?",
              uuid(level),
              uuid(id));
          audit(uuid(id), (String) old.get("levelId"), uuid(level), reason);
          return findMember(id);
        });
  }

  void audit(String id, String from, String to, String reason) {
    db.update(
        "INSERT INTO mem_change(public_id,member_public_id,from_level_id,to_level_id,reason,operator_public_id,changed_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        UUID.randomUUID().toString(),
        id,
        from,
        to,
        reason,
        access.current().staffPublicId());
  }

  public Object history(String id) {
    hq();
    findMember(id);
    return db.query(
        "SELECT * FROM mem_change WHERE member_public_id=? ORDER BY id",
        (r, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", r.getString("public_id"));
          m.put("memberPublicId", r.getString("member_public_id"));
          m.put("fromLevelId", r.getString("from_level_id"));
          m.put("toLevelId", r.getString("to_level_id"));
          m.put("reason", r.getString("reason"));
          m.put("operatorPublicId", r.getString("operator_public_id"));
          m.put("changedAt", r.getTimestamp("changed_at").toInstant().toString());
          return m;
        },
        uuid(id));
  }

  public Object lookup(String customer) {
    var a = access.current();
    if (a == null || !Set.of("HQ", "STORE").contains(a.staffType()))
      throw error(HttpStatus.FORBIDDEN, "需要员工身份");
    return card(customer);
  }

  public Object card(String customer) {
    String c = uuid(customer);
    var rows =
        db.query(
            "SELECT m.public_id,l.name,l.benefits,l.enabled FROM mem_member m JOIN mem_level l ON l.public_id=m.level_public_id WHERE m.customer_public_id=?",
            (r, n) ->
                Map.<String, Object>of(
                    "member",
                    true,
                    "customerPublicId",
                    c,
                    "memberPublicId",
                    r.getString(1),
                    "levelName",
                    r.getString(2),
                    "benefits",
                    r.getString(3),
                    "levelEnabled",
                    r.getBoolean(4),
                    "status",
                    r.getBoolean(4) ? "ACTIVE" : "LEVEL_DISABLED",
                    "displayName",
                    customers
                        .find(c)
                        .map(x -> x.displayName() == null ? "" : x.displayName())
                        .orElse("")),
            c);
    return rows.isEmpty() ? Map.of("member", false, "customerPublicId", c) : rows.get(0);
  }
}
