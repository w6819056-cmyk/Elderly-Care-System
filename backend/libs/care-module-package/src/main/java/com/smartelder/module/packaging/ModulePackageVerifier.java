package com.smartelder.module.packaging;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, fail-closed verification. Never executes or extracts archive entries. */
public final class ModulePackageVerifier {
    public static final int MAX_ARCHIVE_BYTES=64*1024*1024;
    private static final int MAX_FILE_BYTES=32*1024*1024;
    private final Map<String,PublicKey> trustedKeys;
    private final ObjectMapper mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ModulePackageVerifier(Map<String,PublicKey> trustedKeys){this.trustedKeys=Map.copyOf(trustedKeys);}
    public VerifiedModulePackage verify(byte[] archive) {
        if(archive==null||archive.length>MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("模块包超过 64MiB 限制");
        Map<String,byte[]> entries=read(archive);
        byte[] metadata=entries.remove("manifest.json"),signatureBytes=entries.remove("manifest.sig");
        if(metadata==null||signatureBytes==null)throw new IllegalArgumentException("缺少模块清单或签名");
        try {
            var tree=mapper.readTree(metadata);
            if(tree==null||!tree.isObject()||!tree.path("keyId").isTextual())throw new IllegalArgumentException("缺少发布者编号");
            PublicKey key=trustedKeys.get(tree.get("keyId").asText());
            if(key==null)throw new IllegalArgumentException("不受信任的模块发布者");
            Signature signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update(metadata);
            if(!signature.verify(Base64.getDecoder().decode(new String(signatureBytes,StandardCharsets.US_ASCII))))throw new IllegalArgumentException("模块包签名无效");
            ModulePackageManifest manifest=mapper.readValue(metadata,ModulePackageManifest.class);
            if(!entries.keySet().equals(manifest.files().keySet()))throw new IllegalArgumentException("实际文件与清单不一致");
            for(var file:manifest.files().entrySet())if(!sha256(entries.get(file.getKey())).equals(file.getValue()))throw new IllegalArgumentException("模块文件摘要不一致："+file.getKey());
            return new VerifiedModulePackage(manifest,sha256(archive),entries);
        } catch(IllegalArgumentException e){throw e;}catch(GeneralSecurityException e){throw new IllegalArgumentException("模块包签名校验失败",e);}catch(IOException e){if(e.getCause() instanceof IllegalArgumentException invalid)throw invalid;throw new IllegalArgumentException("模块清单格式无效",e);}
    }
    private Map<String,byte[]> read(byte[] archive) {
        Map<String,byte[]> files=new LinkedHashMap<>();Set<String> names=new HashSet<>();int total=0,count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++count>256)throw new IllegalArgumentException("模块包文件数量过多");
                String name=entry.getName();String check=entry.isDirectory()?name.substring(0,name.length()-1):name;
                ModulePackageManifest.validatePath(check);
                if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("重复或大小写冲突的模块文件路径");
                int limit=name.equals("manifest.json")?256*1024:name.equals("manifest.sig")?1024:entry.isDirectory()?0:MAX_FILE_BYTES;
                ByteArrayOutputStream output=new ByteArrayOutputStream();int length;
                while((length=zip.read(buffer))!=-1){total+=length;if(output.size()+length>limit||total>MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("模块包展开大小超过限制");output.write(buffer,0,length);}
                if(!entry.isDirectory())files.put(name,output.toByteArray());
                zip.closeEntry();
            }
            return files;
        }catch(IOException e){throw new IllegalArgumentException("模块压缩包无效",e);}
    }
    public static String sha256(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
