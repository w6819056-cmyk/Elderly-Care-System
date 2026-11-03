package com.smartelder.api.tenant.installation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.config.ModuleDescriptorsConfiguration;
import com.smartelder.module.api.*;
import com.smartelder.module.packaging.*;
import com.smartelder.module.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import static org.assertj.core.api.Assertions.*;

class ExternalModuleBootTest {
 @TempDir Path root;
 @Test void realApplicationRegistrarFindsNewModuleWithoutChangingBuiltinList()throws Exception{
  Path classes=root.resolve("classes");Files.createDirectories(classes);Path src=root.resolve("Entry.java");Files.writeString(src,"""
   package com.smartelder.modules.probe;
   public class Entry implements com.smartelder.module.api.ModuleEntrypoint {
    public java.util.List<Class<?>> configurationClasses(){return java.util.List.of(Config.class);}
    public java.util.Map<String,String> permissionNames(){return java.util.Map.of("probe:record:read","查看验证记录");}
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) public static class Config {
     @org.springframework.context.annotation.Bean public String installedProbe(){return "loaded-through-host";}
    }
   }
   """);assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-encoding","UTF-8","-d",classes.toString(),src.toString())).isZero();
  var jarBuffer=new ByteArrayOutputStream();try(var jar=new JarOutputStream(jarBuffer);var walk=Files.walk(classes)){for(Path p:walk.filter(Files::isRegularFile).toList()){jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));jar.write(Files.readAllBytes(p));jar.closeEntry();}}
  byte[] jar=jarBuffer.toByteArray();var manifest=new ModulePackageManifest(1,"test",new ModuleDescriptor("probe","验证","1.0.0",99,List.of(),Set.of("probe:record:read")),"2.0.0","3.0.0","com.smartelder.modules.probe.Entry",Map.of(),List.of(),Map.of("backend/module.jar",ModulePackageVerifier.sha256(jar)));
  Path cache=root.resolve("cache"),trust=root.resolve("trust"),runtime=root.resolve("runtime");Files.createDirectories(cache);Files.createDirectories(trust);var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Files.writeString(trust.resolve("test.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
  byte[] metadata=new ObjectMapper().writeValueAsBytes(manifest);var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(metadata);var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var e:Map.of("manifest.json",metadata,"manifest.sig",Base64.getEncoder().encode(signer.sign()),"backend/module.jar",jar).entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
  byte[] archive=output.toByteArray();String sha=ModulePackageVerifier.sha256(archive);Files.write(cache.resolve(sha+".caremod"),archive);var files=new ModuleRuntimeFiles(runtime,cache,trust);files.extract(files.verify(sha));files.index(new ModuleRuntimeFiles.Index(1,Map.of("probe",new ModuleRuntimeFiles.Entry(UUID.randomUUID().toString(),sha))));
  try(var context=new AnnotationConfigApplicationContext()){
   context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("runtime-test",Map.of("app.module-install.directory",cache.toString(),"app.module-install.runtime-directory",runtime.toString(),"app.module-install.trust-directory",trust.toString())));context.register(ModuleDescriptorsConfiguration.class);context.refresh();
   assertThat(context.getBean("installedProbe")).isEqualTo("loaded-through-host");assertThat(context.getBean(ModuleRegistry.class).contains("probe")).isTrue();assertThat(context.getBeansOfType(ModulePermissionLabels.class).values().stream().anyMatch(p->p.permissionNames().get("probe:record:read").equals("查看验证记录"))).isTrue();
  }
 }
}
