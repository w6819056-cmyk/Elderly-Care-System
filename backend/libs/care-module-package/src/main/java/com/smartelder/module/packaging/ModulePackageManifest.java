package com.smartelder.module.packaging;

import com.smartelder.module.api.ModuleDescriptor;
import java.math.BigInteger;
import java.util.*;

/** Signed release metadata. Runtime compatibility and authorization are separate checks. */
public record ModulePackageManifest(int formatVersion, String keyId, ModuleDescriptor module,
        String minCoreVersion, String maxCoreVersionExclusive, String entryClass,
        Map<String,String> frontendEntries, List<String> migrations, Map<String,String> files) {
    public ModulePackageManifest {
        if (formatVersion != 1) throw new IllegalArgumentException("不支持的模块包协议版本");
        if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("发布者编号无效");
        Objects.requireNonNull(module,"缺少模块定义");
        version(module.version()); version(minCoreVersion); version(maxCoreVersionExclusive);
        if (compare(minCoreVersion,maxCoreVersionExclusive)>=0) throw new IllegalArgumentException("底座版本范围无效");
        if (entryClass == null || !entryClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) throw new IllegalArgumentException("后端入口类无效");
        frontendEntries=Map.copyOf(Objects.requireNonNull(frontendEntries,"缺少前端入口清单"));
        migrations=List.copyOf(Objects.requireNonNull(migrations,"缺少迁移清单"));
        files=Map.copyOf(Objects.requireNonNull(files,"缺少文件清单"));
        if (files.size()>254 || !files.containsKey("backend/module.jar")) throw new IllegalArgumentException("缺少后端程序或文件清单过大");
        Set<String> normalized = new HashSet<>();
        for(var file:files.entrySet()) {
            validatePath(file.getKey());
            if (!normalized.add(file.getKey().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("文件路径大小写冲突");
            if (!file.getKey().startsWith("backend/")&&!file.getKey().startsWith("frontend/")&&!file.getKey().startsWith("migrations/")) throw new IllegalArgumentException("文件路径不属于模块目录");
            if (file.getValue()==null || !file.getValue().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("文件摘要格式无效");
        }
        for(var entry:frontendEntries.entrySet()) {
            if(!Set.of("HQ","STORE","CUSTOMER").contains(entry.getKey()) || !files.containsKey(entry.getValue()) || !entry.getValue().startsWith("frontend/") || !entry.getValue().endsWith(".js")) throw new IllegalArgumentException("前端入口未包含在文件清单中");
        }
        BigInteger previous=BigInteger.ZERO;
        for(String path:migrations) {
            if(!files.containsKey(path)||!path.matches("migrations/V[1-9][0-9]*__[A-Za-z0-9_]+\\.sql")) throw new IllegalArgumentException("迁移文件声明无效");
            BigInteger current=new BigInteger(path.substring("migrations/V".length(),path.indexOf("__")));
            if(current.compareTo(previous)<=0) throw new IllegalArgumentException("迁移版本必须唯一且升序");
            previous=current;
        }
        if(!files.keySet().stream().filter(p->p.startsWith("migrations/")).collect(java.util.stream.Collectors.toSet()).equals(Set.copyOf(migrations)))throw new IllegalArgumentException("所有迁移文件必须在迁移清单中声明");
        for(String permission:module.permissionCodes()) if(!permission.startsWith(module.code()+":")) throw new IllegalArgumentException("模块权限不能声明其他模块的权限");
        Set<String> dependencies=new HashSet<>();
        for(var dependency:module.dependencies()) {version(dependency.minimumVersion());if(!dependencies.add(dependency.moduleCode()))throw new IllegalArgumentException("重复模块依赖");}
    }
    public boolean supportsCore(String version) {version(version);return compare(version,minCoreVersion)>=0&&compare(version,maxCoreVersionExclusive)<0;}
    static void validatePath(String path) {
        if(path==null||path.length()>240||!path.matches("[A-Za-z0-9_./-]+")||path.startsWith("/")||path.endsWith("/")||path.contains("//")) throw new IllegalArgumentException("非法模块文件路径");
        for(String part:path.split("/")) if(part.equals(".")||part.equals("..")||part.endsWith(".")||part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw new IllegalArgumentException("非法模块文件路径");
    }
    private static void version(String value) {if(value==null||value.length()>30||!value.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))throw new IllegalArgumentException("模块包使用三段纯数字版本号");}
    private static int compare(String a,String b) {var left=a.split("\\.");var right=b.split("\\.");for(int i=0;i<3;i++){int c=new BigInteger(left[i]).compareTo(new BigInteger(right[i]));if(c!=0)return c;}return 0;}
}
