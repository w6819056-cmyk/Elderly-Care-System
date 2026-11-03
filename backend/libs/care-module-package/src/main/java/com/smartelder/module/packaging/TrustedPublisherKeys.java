package com.smartelder.module.packaging;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

public final class TrustedPublisherKeys {
 private TrustedPublisherKeys(){}
 public static Map<String,PublicKey> load(Path directory){
  if(!Files.exists(directory))return Map.of();Map<String,PublicKey> result=new HashMap<>();
  try(var files=Files.list(directory)){for(Path p:files.filter(f->f.getFileName().toString().matches("[A-Za-z0-9_-]{1,64}\\.pem")).toList()){
   if(Files.isSymbolicLink(p)||!Files.isRegularFile(p)||Files.size(p)>4096)throw new IllegalArgumentException("可信公钥文件无效");
   String pem=Files.readString(p);if(!pem.contains("-----BEGIN PUBLIC KEY-----"))throw new IllegalArgumentException("可信目录只能存放发布公钥");
   byte[] bytes=Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""));String filename=p.getFileName().toString();result.put(filename.substring(0,filename.length()-4),KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes)));
  }}catch(IOException|GeneralSecurityException e){throw new IllegalStateException("无法加载可信发布公钥",e);}return Map.copyOf(result);
 }
}
