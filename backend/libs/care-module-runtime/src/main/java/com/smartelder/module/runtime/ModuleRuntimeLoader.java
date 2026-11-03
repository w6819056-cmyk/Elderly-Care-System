package com.smartelder.module.runtime;
import com.smartelder.module.api.*;
import com.smartelder.module.packaging.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

public final class ModuleRuntimeLoader implements AutoCloseable {
 private VerifiedModuleClassLoader loader;
 private static Map<String,byte[]> jarResources(byte[] bytes,String code,String entryClass)throws Exception{
  String prefix="com/smartelder/modules/"+code.replace('-','_')+"/";
  if(!entryClass.replace('.','/').startsWith(prefix))throw new IllegalArgumentException("Module entry class must be inside "+prefix);
  Map<String,byte[]> resources=new HashMap<>();int total=0;
  try(var jar=new JarInputStream(new ByteArrayInputStream(bytes))){
   if(jar.getManifest()!=null&&jar.getManifest().getMainAttributes().getValue("Class-Path")!=null)throw new IllegalArgumentException("Module JAR Class-Path is not allowed");
   JarEntry entry;while((entry=jar.getNextJarEntry())!=null){String name=entry.getName();if(entry.isDirectory())continue;
    if(name.endsWith(".class")&&!name.startsWith(prefix))throw new IllegalArgumentException("Module JAR may only declare its own namespace");
    if(name.startsWith("META-INF/services/")||name.startsWith("META-INF/spring")||name.startsWith("BOOT-INF/")||name.endsWith(".jar")||name.startsWith("/")||name.contains("..")||name.contains("\\"))throw new IllegalArgumentException("Unsupported module JAR resource");
    byte[] value=jar.readNBytes(32*1024*1024+1);total+=value.length;
    if(value.length>32*1024*1024||total>64*1024*1024||resources.putIfAbsent(name,value)!=null)throw new IllegalArgumentException("Duplicate or oversized JAR resource");
    if(name.equalsIgnoreCase("META-INF/MANIFEST.MF")&&new Manifest(new ByteArrayInputStream(value)).getMainAttributes().getValue("Class-Path")!=null)throw new IllegalArgumentException("Module JAR Class-Path is not allowed");
   }
  }
  if(!resources.containsKey(entryClass.replace('.','/')+".class"))throw new IllegalArgumentException("Module entry class is absent");return resources;
 }
 public static void validateJar(byte[] bytes,String code,String entryClass,ClassLoader parent)throws Exception{
  try(var check=new VerifiedModuleClassLoader(jarResources(bytes,code,entryClass),parent)){
   Class<?> type=Class.forName(entryClass,false,check);
   if(!ModuleEntrypoint.class.isAssignableFrom(type)||type.getClassLoader()!=check)throw new IllegalArgumentException("Module entry must implement host ModuleEntrypoint");type.getConstructor();
  }
 }
 public List<Loaded> load(ModuleRuntimeFiles files,String coreVersion,ClassLoader parent)throws Exception{
  var index=files.index();List<VerifiedModulePackage> packages=new ArrayList<>();Map<String,byte[]> resources=new HashMap<>();
  for(var item:new TreeMap<>(index.modules()).entrySet()){
   var verified=files.verify(item.getValue().sha256());var m=verified.manifest();
   if(!m.module().code().equals(item.getKey())||!m.supportsCore(coreVersion))throw new IllegalStateException("Installed module identity/core version mismatch");
   files.verifyPayload(files.payload(verified.packageSha256()),verified);
   byte[] jar=verified.file("backend/module.jar");validateJar(jar,m.module().code(),m.entryClass(),parent);
   for(var resource:jarResources(jar,m.module().code(),m.entryClass()).entrySet()){byte[] prior=resources.putIfAbsent(resource.getKey(),resource.getValue());if(prior!=null&&resource.getKey().endsWith(".class"))throw new IllegalArgumentException("Duplicate module class");}packages.add(verified);
  }
  new ModuleRegistry(packages.stream().map(p->p.manifest().module()).toList());loader=new VerifiedModuleClassLoader(resources,parent);List<Loaded> result=new ArrayList<>();
  try{for(var p:packages){var m=p.manifest();var type=Class.forName(m.entryClass(),true,loader);ModuleEntrypoint entry=(ModuleEntrypoint)type.getConstructor().newInstance();
   var configs=List.copyOf(entry.configurationClasses());String prefix="com.smartelder.modules."+m.module().code().replace('-','_')+".";
   if(configs.isEmpty()||configs.stream().anyMatch(c->!c.getName().startsWith(prefix)||c.getClassLoader()!=loader))throw new IllegalArgumentException("Module config must belong to its namespace");
   var labels=Map.copyOf(entry.permissionNames());if(!labels.keySet().equals(m.module().permissionCodes())||labels.values().stream().anyMatch(s->s.isBlank()||s.length()>100))throw new IllegalArgumentException("Module must name every declared permission");
   result.add(new Loaded(m,index.modules().get(m.module().code()),configs,labels));
  }return List.copyOf(result);}catch(Exception|LinkageError e){close();throw e;}
 }
 @Override public void close(){if(loader!=null)loader.close();}
 public record Loaded(ModulePackageManifest manifest,ModuleRuntimeFiles.Entry installation,List<Class<?>> configurations,Map<String,String> permissionNames){}
}
