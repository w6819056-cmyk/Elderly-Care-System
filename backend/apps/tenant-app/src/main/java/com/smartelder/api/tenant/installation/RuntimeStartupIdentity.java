package com.smartelder.api.tenant.installation;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Component
public class RuntimeStartupIdentity {
 private final String startupId=UUID.randomUUID().toString();
 private final long pid=ProcessHandle.current().pid();
 private final String processStartedAt=ProcessHandle.current().info().startInstant().orElseThrow(()->new IllegalStateException("Process start identity unavailable")).toString();
 private volatile boolean ready;
 public Map<String,Object> identity(){return Map.of("startupId",startupId,"pid",pid,"processStartedAt",processStartedAt);}
 public void ready(){ready=true;}
 @RestController
 public static class Health {
  private final RuntimeStartupIdentity identity;public Health(RuntimeStartupIdentity identity){this.identity=identity;}
  @GetMapping("/api/public/runtime-health") public ResponseEntity<?> health(){return ResponseEntity.status(identity.ready?200:503).body(identity.identity());}
 }
}
