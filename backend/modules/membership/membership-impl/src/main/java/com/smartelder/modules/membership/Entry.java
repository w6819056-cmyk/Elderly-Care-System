package com.smartelder.modules.membership;

import com.smartelder.module.api.ModuleEntrypoint;
import java.util.*;

public class Entry implements ModuleEntrypoint {
  public List<Class<?>> configurationClasses() {
    return List.of(MembershipConfiguration.class);
  }

  public Map<String, String> permissionNames() {
    return Map.of(
        "membership:level:read",
        "查看会员等级",
        "membership:level:write",
        "管理会员等级",
        "membership:member:read",
        "查看会员",
        "membership:member:write",
        "管理会员",
        "membership:history:read",
        "查看改级流水",
        "membership:identity:read",
        "查询会员身份");
  }
}
