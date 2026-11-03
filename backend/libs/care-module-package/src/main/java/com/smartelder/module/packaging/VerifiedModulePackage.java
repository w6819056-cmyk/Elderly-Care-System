package com.smartelder.module.packaging;
import java.util.*;

/** Immutable verified bytes; this object does not represent an installed module. */
public final class VerifiedModulePackage {
    private final ModulePackageManifest manifest;
    private final String packageSha256;
    private final Map<String,byte[]> files;
    VerifiedModulePackage(ModulePackageManifest manifest,String packageSha256,Map<String,byte[]> files){
        this.manifest=manifest;this.packageSha256=packageSha256;
        Map<String,byte[]> copy=new HashMap<>();files.forEach((k,v)->copy.put(k,v.clone()));this.files=Map.copyOf(copy);
    }
    public ModulePackageManifest manifest(){return manifest;}
    public String packageSha256(){return packageSha256;}
    public Set<String> paths(){return files.keySet();}
    public byte[] file(String path){byte[] result=files.get(path);if(result==null)throw new IllegalArgumentException("模块文件不存在");return result.clone();}
}
