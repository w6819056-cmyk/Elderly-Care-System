package com.smartelder.platform.api.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.PlatformApplication;
import com.smartelder.module.api.ModuleDescriptor;
import com.smartelder.module.packaging.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test") @SpringBootTest(classes=PlatformApplication.class) @AutoConfigureMockMvc
class ModulePackageIntegrationTest {
 static final Path directory;static final KeyPair key;
 static {try{directory=Files.createTempDirectory("care-package-test-");key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Files.writeString(directory.resolve("test-key.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("platform.module-packages.directory",()->directory.resolve("packages").toString());r.add("platform.module-packages.trust-directory",directory::toString);}
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
 @Test void verifiesStoresDownloadsAndRejectsOverwrite()throws Exception{
  var response=mvc.perform(post("/api/platform/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"ChangeMe-Platform-2026\"}")).andExpect(status().isOk()).andReturn();
  String token=mapper.readTree(response.getResponse().getContentAsString()).path("data").path("accessToken").asText();
  String code="pkg"+UUID.randomUUID().toString().substring(0,8);
  mvc.perform(post("/api/platform/v1/modules").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("code",code,"name","验证模块","description","安装协议测试","menuOrder",90,"trialEnabled",false,"dependencies",List.of(),"prices",List.of())))).andExpect(status().isCreated());
  byte[] jar={1,2,3};var manifest=new ModulePackageManifest(1,"test-key",new ModuleDescriptor(code,"验证模块","1.0.0",90,List.of(),Set.of()),"2.0.0","3.0.0","com.example.Demo",Map.of(),List.of(),Map.of("backend/module.jar",ModulePackageVerifier.sha256(jar)));
  byte[] metadata=mapper.writeValueAsBytes(manifest);var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(metadata);
  var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var file:Map.of("manifest.json",metadata,"manifest.sig",Base64.getEncoder().encode(signer.sign()),"backend/module.jar",jar).entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}}
  mvc.perform(post("/api/platform/v1/module-packages").contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes.toByteArray())).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/platform/v1/module-packages").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[]{0})).andExpect(status().isBadRequest());
  mvc.perform(post("/api/platform/v1/module-packages").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes.toByteArray())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("VERIFIED"));
  mvc.perform(get("/api/platform/v1/module-packages/"+code+"/1.0.0/download").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().bytes(bytes.toByteArray()));
  mvc.perform(post("/api/platform/v1/module-packages").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes.toByteArray())).andExpect(status().isConflict());
  mvc.perform(get("/api/platform/v1/instances/not-an-instance/module-packages")).andExpect(status().isForbidden());
  mvc.perform(get("/api/platform/v1/instances/not-an-instance/module-packages/"+code+"/1.0.0").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(post("/api/platform/v1/module-packages/"+code+"/1.0.0/publish").header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(post("/api/platform/v1/module-packages/"+code+"/1.0.0/publish").header("Authorization","Bearer "+token)).andExpect(status().isConflict());
 }
}
