package com.smartelder.modules.membership;

import com.smartelder.module.api.ModuleOperation;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenant/v1/modules/membership")
public class MembershipController {
  final MembershipService s;

  public MembershipController(MembershipService s) {
    this.s = s;
  }

  static Object ok(Object data) {
    return Map.of("code", "OK", "data", data);
  }

  public record Level(String name, int rank, String benefits, boolean enabled, Long version) {}

  public record Join(String customerPublicId, String levelId, String reason) {}

  public record Change(String levelId, String reason, Long version) {}

  @ModuleOperation(resource = "level", operation = "read")
  @GetMapping("/levels")
  public Object levels() {
    return ok(s.levels());
  }

  @ModuleOperation(resource = "level", operation = "write")
  @PostMapping("/levels")
  public Object createLevel(@RequestBody Level x) {
    return ok(s.saveLevel(null, x.name(), x.rank(), x.benefits(), x.enabled(), null));
  }

  @ModuleOperation(resource = "level", operation = "write")
  @PutMapping("/levels/{id}")
  public Object updateLevel(@PathVariable String id, @RequestBody Level x) {
    return ok(s.saveLevel(id, x.name(), x.rank(), x.benefits(), x.enabled(), x.version()));
  }

  @ModuleOperation(resource = "member", operation = "read")
  @GetMapping("/customers")
  public Object customers(@RequestParam(defaultValue = "") String q) {
    return ok(s.searchCustomers(q));
  }

  @ModuleOperation(resource = "member", operation = "read")
  @GetMapping("/members")
  public Object members(@RequestParam(defaultValue = "") String q) {
    return ok(s.members(q));
  }

  @ModuleOperation(resource = "member", operation = "write")
  @PostMapping("/members")
  public Object enroll(@RequestBody Join x) {
    return ok(s.enroll(x.customerPublicId(), x.levelId(), x.reason()));
  }

  @ModuleOperation(resource = "member", operation = "write")
  @PutMapping("/members/{id}/level")
  public Object change(@PathVariable String id, @RequestBody Change x) {
    return ok(s.change(id, x.levelId(), x.reason(), x.version()));
  }

  @ModuleOperation(resource = "history", operation = "read")
  @GetMapping("/members/{id}/history")
  public Object history(@PathVariable String id) {
    return ok(s.history(id));
  }

  @ModuleOperation(resource = "identity", operation = "read")
  @GetMapping("/lookup")
  public Object lookup(@RequestParam String customerPublicId) {
    return ok(s.lookup(customerPublicId));
  }
}
