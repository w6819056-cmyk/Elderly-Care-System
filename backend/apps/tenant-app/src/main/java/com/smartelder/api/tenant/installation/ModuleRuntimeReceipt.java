package com.smartelder.api.tenant.installation;
import com.smartelder.config.InstalledModulesRegistrar.RuntimeState;
import com.smartelder.module.runtime.ModuleRuntimeFiles;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ModuleRuntimeReceipt {
 private final RuntimeState runtime;
 private final RuntimeStartupIdentity identity;
 public ModuleRuntimeReceipt(RuntimeState runtime,RuntimeStartupIdentity identity){this.runtime=runtime;this.identity=identity;}
 @EventListener(ApplicationReadyEvent.class) public void ready(ApplicationReadyEvent event)throws Exception{
  int port=event.getApplicationContext() instanceof org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext web?web.getWebServer().getPort():-1;
  var receipt=new java.util.HashMap<String,Object>(identity.identity());receipt.put("readyAt",Instant.now().toString());receipt.put("port",port);receipt.put("modules",runtime.loaded().stream().collect(Collectors.toMap(m->m.manifest().module().code(),m->m.installation())));
  ModuleRuntimeFiles.atomicJson(runtime.files().root().resolve("runtime-ready.json"),receipt);identity.ready();
 }
}
