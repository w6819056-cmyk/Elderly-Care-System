package com.smartelder.modules.hello_module;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class HelloConfiguration {
 @Bean public HelloController helloModuleController(){return new HelloController();}
}
