package com.smartelder.modules.hello_module;
import com.smartelder.module.api.ModuleOperation;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class HelloController {
 @GetMapping("/api/tenant/v1/modules/hello-module/records")
 @ModuleOperation(resource="HelloResource",operation="read")
 public Object records(){return Map.of("code","OK","data",List.of(Map.of("message","外置 JAR 接口已加载，授权与员工权限校验通过","version","1.0.0")));}
}
