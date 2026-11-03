package com.smartelder.module.runtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.module.api.*;
import com.smartelder.module.packaging.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

class ModuleRuntimeLoaderTest {
 @TempDir Path root;
 ModuleRuntimeFiles files;String sha;
 @BeforeEach void packageRealPlugin()throws Exception{
  Path source=root.resolve("Entry.java"),classes=root.resolve("classes");Files.createDirectories(classes);
  Files.writeString(source,"""
   package com.smartelder.modules.probe;
   public class Entry implements com.smartelder.module.api.ModuleEntrypoint {
    public java.util.List<Class<?>> configurationClasses(){return java.util.List.of(Config.class);}
    public java.util.Map<String,String> permissionNames(){return java.util.Map.of();}
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    public static class Config { @org.springframework.context.annotation.Bean("probeGreeting") public String greeting(){return "ready";} }
   }
   """);
  int result=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),source.toString());assertThat(result).isZero();
  var jarBytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(jarBytes);var paths=Files.walk(classes)){for(Path p:paths.filter(Files::isRegularFile).toList()){jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));jar.write(Files.readAllBytes(p));jar.closeEntry();}}
  var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Path trust=root.resolve("trust"),cache=root.resolve("cache");Files.createDirectories(trust);Files.createDirectories(cache);Files.writeString(trust.resolve("publisher.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
  byte[] jar=jarBytes.toByteArray();var m=new ModulePackageManifest(1,"publisher",new ModuleDescriptor("probe","运行验证","1.0.0",99,List.of(),Set.of()),"2.0.0","3.0.0","com.smartelder.modules.probe.Entry",Map.of(),List.of(),Map.of("backend/module.jar",ModulePackageVerifier.sha256(jar)));
  byte[] metadata=new ObjectMapper().writeValueAsBytes(m);var signature=Signature.getInstance("Ed25519");signature.initSign(keys.getPrivate());signature.update(metadata);var output=new ByteArrayOutputStream();
  try(var zip=new ZipOutputStream(output)){for(var e:Map.of("manifest.json",metadata,"manifest.sig",Base64.getEncoder().encode(signature.sign()),"backend/module.jar",jar).entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
  byte[] bytes=output.toByteArray();sha=ModulePackageVerifier.sha256(bytes);Files.write(cache.resolve(sha+".caremod"),bytes);files=new ModuleRuntimeFiles(root.resolve("runtime"),cache,trust);files.extract(files.verify(sha));files.index(new ModuleRuntimeFiles.Index(1,Map.of("probe",new ModuleRuntimeFiles.Entry(UUID.randomUUID().toString(),sha))));
 }
 @Test void loadsActualExternalJarAndSpringConfiguration()throws Exception{
  try(var loader=new ModuleRuntimeLoader();var context=new AnnotationConfigApplicationContext()){
   var loaded=loader.load(files,"2.0.0",getClass().getClassLoader());assertThat(loaded).hasSize(1);loaded.get(0).configurations().forEach(context::register);context.refresh();assertThat(context.getBean("probeGreeting")).isEqualTo("ready");
  }
 }
 @Test void detectsExtractedAndCachedTampering()throws Exception{
  Files.write(files.payload(sha).resolve("backend/module.jar"),new byte[]{0});try(var loader=new ModuleRuntimeLoader()){assertThatThrownBy(()->loader.load(files,"2.0.0",getClass().getClassLoader())).hasMessageContaining("changed");}
  Files.write(root.resolve("cache").resolve(sha+".caremod"),new byte[]{0});assertThatThrownBy(()->files.verify(sha)).hasMessageContaining("digest");
 }
 @Test void rejectsUnsupportedCoreVersion()throws Exception{try(var loader=new ModuleRuntimeLoader()){assertThatThrownBy(()->loader.load(files,"3.0.0",getClass().getClassLoader())).hasMessageContaining("core version mismatch");}}
 @Test void emptyIndexLoadsNoModules()throws Exception{var empty=new ModuleRuntimeFiles(root.resolve("empty"),root.resolve("cache"),root.resolve("trust"));try(var loader=new ModuleRuntimeLoader()){assertThat(loader.load(empty,"2.0.0",getClass().getClassLoader())).isEmpty();}}
 @Test void laterDiskReplacementCannotChangeAlreadyVerifiedClassSource()throws Exception{
  try(var loader=new ModuleRuntimeLoader();var context=new AnnotationConfigApplicationContext()){
   var loaded=loader.load(files,"2.0.0",getClass().getClassLoader());Files.write(files.payload(sha).resolve("backend/module.jar"),new byte[]{0});
   loaded.get(0).configurations().forEach(context::register);context.refresh();assertThat(context.getBean("probeGreeting")).isEqualTo("ready");
  }
 }
}
