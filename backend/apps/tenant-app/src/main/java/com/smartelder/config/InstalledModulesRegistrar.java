package com.smartelder.config;

import com.smartelder.module.api.ModulePermissionLabels;
import com.smartelder.module.runtime.*;
import org.springframework.beans.factory.support.*;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import java.nio.file.Path;
import java.util.List;

public class InstalledModulesRegistrar implements ImportBeanDefinitionRegistrar,EnvironmentAware {
 private Environment environment;
 @Override public void setEnvironment(Environment environment){this.environment=environment;}
 @Override public void registerBeanDefinitions(AnnotationMetadata metadata,BeanDefinitionRegistry registry){
  var files=new ModuleRuntimeFiles(Path.of(environment.getProperty("app.module-install.runtime-directory","data/module-runtime")),Path.of(environment.getProperty("app.module-install.directory","data/module-install")),Path.of(environment.getProperty("app.module-install.trust-directory","data/module-publisher-keys")));
  var loader=new ModuleRuntimeLoader();
  try {
   var loaded=loader.load(files,environment.getProperty("app.module-install.core-version","2.1.0"),getClass().getClassLoader());
   var state=new RuntimeState(files,loader,loaded);
   RootBeanDefinition stateBean=new RootBeanDefinition(RuntimeState.class,()->state);stateBean.setDestroyMethodName("close");registry.registerBeanDefinition("careInstalledRuntime",stateBean);
   for(var module:loaded){String code=module.manifest().module().code();int index=0;
    for(Class<?> config:module.configurations()){RootBeanDefinition definition=new RootBeanDefinition(config);registry.registerBeanDefinition("careExternal_"+code+"_"+(index++),definition);}
    registry.registerBeanDefinition("careExternalLabels_"+code,new RootBeanDefinition(ModulePermissionLabels.class,()->(ModulePermissionLabels)module::permissionNames));
   }
  }catch(Exception|LinkageError e){try{loader.close();}catch(Exception ignored){}throw new IllegalStateException("Installed module startup validation failed; installation recovery is required",e);}
 }
 public record RuntimeState(ModuleRuntimeFiles files,ModuleRuntimeLoader loader,List<ModuleRuntimeLoader.Loaded> loaded) implements AutoCloseable {
  @Override public void close()throws Exception{loader.close();}
 }
}
