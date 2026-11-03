package com.smartelder.module.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.module.api.ModuleDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModulePackageVerifierTest {
 private final KeyPair keys;
 private final ObjectMapper mapper=new ObjectMapper();
 ModulePackageVerifierTest() throws Exception {keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
 private Map<String,byte[]> payload(){return new LinkedHashMap<>(Map.of("backend/module.jar",new byte[]{1,2,3},"frontend/hq.js","export default {}".getBytes(StandardCharsets.UTF_8)));}
 private ModulePackageManifest manifest(Map<String,byte[]> files) throws Exception {
  Map<String,String> hashes=new LinkedHashMap<>();for(var f:files.entrySet())hashes.put(f.getKey(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(f.getValue())));
  return new ModulePackageManifest(1,"publisher-v1",new ModuleDescriptor("membership","会员运营","1.0.0",100,List.of(),Set.of("membership:level:view")),"2.0.0","3.0.0","com.example.MemberModule",Map.of("HQ","frontend/hq.js"),List.of(),hashes);
 }
 private byte[] zip(byte[] manifest,Map<String,byte[]> files) throws Exception {
  Signature signature=Signature.getInstance("Ed25519");signature.initSign(keys.getPrivate());signature.update(manifest);
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){Map<String,byte[]> all=new LinkedHashMap<>();all.put("manifest.json",manifest);all.put("manifest.sig",Base64.getEncoder().encode(signature.sign()));all.putAll(files);for(var item:all.entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}return out.toByteArray();
 }
 private ModulePackageVerifier verifier(){return new ModulePackageVerifier(Map.of("publisher-v1",keys.getPublic()));}
 @Test void validatesSignedPackageWithoutExecutingPayload() throws Exception {
  var files=payload();var result=verifier().verify(zip(mapper.writeValueAsBytes(manifest(files)),files));assertThat(result.manifest().module().code()).isEqualTo("membership");assertThat(result.file("backend/module.jar")).containsExactly(1,2,3);
  byte[] copy=result.file("backend/module.jar");copy[0]=99;assertThat(result.file("backend/module.jar")[0]).isEqualTo((byte)1);
 }
 @Test void rejectsTamperedPayload() throws Exception {var files=payload();byte[] m=mapper.writeValueAsBytes(manifest(files));files.put("backend/module.jar",new byte[]{9});assertThatThrownBy(()->verifier().verify(zip(m,files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("摘要");}
 @Test void rejectsUntrustedPublisher() throws Exception {var files=payload();assertThatThrownBy(()->new ModulePackageVerifier(Map.of()).verify(zip(mapper.writeValueAsBytes(manifest(files)),files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("发布者");}
 @Test void rejectsForgedSignature() throws Exception {var files=payload();var foreign=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();assertThatThrownBy(()->new ModulePackageVerifier(Map.of("publisher-v1",foreign.getPublic())).verify(zip(mapper.writeValueAsBytes(manifest(files)),files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("签名");}
 @Test void rejectsUndeclaredAndMissingFiles() throws Exception {var files=payload();byte[] m=mapper.writeValueAsBytes(manifest(files));files.put("frontend/rogue.js",new byte[]{1});assertThatThrownBy(()->verifier().verify(zip(m,files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("清单");files.remove("frontend/rogue.js");files.remove("frontend/hq.js");assertThatThrownBy(()->verifier().verify(zip(m,files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("清单");}
 @Test void rejectsPathTraversalAndWindowsPaths() throws Exception {for(String name:List.of("../outside","/absolute","C:/drive","frontend\\bad.js","frontend/./bad.js","frontend//bad.js")){var files=payload();byte[] m=mapper.writeValueAsBytes(manifest(files));files.put(name,new byte[]{1});assertThatThrownBy(()->verifier().verify(zip(m,files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("路径");}}
 @Test void rejectsUnknownAndDuplicateJsonFields() throws Exception {var files=payload();String m=mapper.writeValueAsString(manifest(files));for(String bad:List.of(m.replaceFirst("\\{","{\"unexpected\":1,"),m.replaceFirst("\\{","{\"formatVersion\":1,"))){assertThatThrownBy(()->verifier().verify(zip(bad.getBytes(StandardCharsets.UTF_8),files))).isInstanceOf(IllegalArgumentException.class);}}
 @Test void rejectsForeignPermissionNamespace() throws Exception {var files=payload();String m=mapper.writeValueAsString(manifest(files)).replace("membership:level:view","institution:level:view");assertThatThrownBy(()->verifier().verify(zip(m.getBytes(StandardCharsets.UTF_8),files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("权限");}
 @Test void rejectsOversizedManifestAndArchive() {assertThatThrownBy(()->verifier().verify(new byte[64*1024*1024+1])).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsLargeMetadataAndCaseCollisions() throws Exception {
  var files=payload();assertThatThrownBy(()->verifier().verify(zip(new byte[256*1024+1],files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("大小");
  byte[] m=mapper.writeValueAsBytes(manifest(files));files.put("BACKEND/module.jar",new byte[]{1});assertThatThrownBy(()->verifier().verify(zip(m,files))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("冲突");
 }
 @Test void checksCoreCompatibilityRange() throws Exception {
  var m=manifest(payload());assertThat(m.supportsCore("2.0.0")).isTrue();assertThat(m.supportsCore("2.9.9")).isTrue();assertThat(m.supportsCore("3.0.0")).isFalse();assertThat(m.supportsCore("1.9.9")).isFalse();
 }
}
