package com.smartelder.platform.packages;

import com.smartelder.module.packaging.*;
import com.smartelder.common.ErrorCode;
import com.smartelder.platform.core.common.PlatformApiException;
import com.smartelder.platform.core.common.PlatformAuditService;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;



@Service
public class ModulePackageStore {
 private final JdbcClient jdbc;private final Path root,trust;private final PlatformAuditService audit;
 public ModulePackageStore(JdbcClient jdbc,PlatformAuditService audit,@Value("${platform.module-packages.directory:data/module-packages}") String root,@Value("${platform.module-packages.trust-directory:data/module-publisher-keys}") String trust){this.jdbc=jdbc;this.audit=audit;this.root=Path.of(root).toAbsolutePath().normalize();this.trust=Path.of(trust).toAbsolutePath().normalize();}
 private ModulePackageVerifier verifier(){
  Map<String,PublicKey> keys=new HashMap<>();if(!Files.exists(trust))return new ModulePackageVerifier(keys);
  try(var paths=Files.list(trust)){for(Path path:paths.filter(p->p.getFileName().toString().matches("[A-Za-z0-9_-]{1,64}\\.pem")).toList()){
   if(Files.isSymbolicLink(path)||Files.size(path)>4096)throw new IllegalArgumentException("可信发布公钥配置无效");
   String pem=Files.readString(path).replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
   String name=path.getFileName().toString();keys.put(name.substring(0,name.length()-4),KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem))));
  }}catch(IOException|GeneralSecurityException e){throw new IllegalStateException("无法读取可信发布公钥配置",e);}return new ModulePackageVerifier(keys);
 }
 @Transactional public PackageView upload(byte[] bytes,PlatformPrincipal actor,String trace){
  VerifiedModulePackage verified;try{verified=verifier().verify(bytes);}catch(IllegalArgumentException e){throw new PlatformApiException(ErrorCode.INVALID_REQUEST,e.getMessage());}var manifest=verified.manifest();String code=manifest.module().code(),version=manifest.module().version();
  long moduleId=jdbc.sql("SELECT id FROM pf_module_definition WHERE code=:code FOR UPDATE").param("code",code).query(Long.class).optional().orElseThrow(()->new PlatformApiException(ErrorCode.MODULE_NOT_FOUND,"请先在销售目录创建模块："+code));
  var existingVersion=jdbc.sql("SELECT min_core_version FROM pf_module_version WHERE module_id=:id AND version=:v").param("id",moduleId).param("v",version).query(String.class).optional();
  if(existingVersion.isPresent()&&!existingVersion.get().equals(manifest.minCoreVersion()))throw new PlatformApiException(ErrorCode.INVALID_REQUEST,"包内底座最低版本与销售目录版本不一致");
  if(existingVersion.isEmpty())jdbc.sql("INSERT INTO pf_module_version(module_id,version,min_core_version,created_at) VALUES(:id,:v,:core,:now)").param("id",moduleId).param("v",version).param("core",manifest.minCoreVersion()).param("now",Timestamp.from(Instant.now())).update();
  if(jdbc.sql("SELECT COUNT(*) FROM pf_module_package WHERE module_code=:code AND package_version=:v").param("code",code).param("v",version).query(Long.class).single()>0)throw new PlatformApiException(ErrorCode.MODULE_VERSION_CONFLICT,"该模块版本已收录，不允许覆盖");
  // Write content-addressed bytes first: a rolled-back DB transaction can leave an orphan,
  // but never a database record pointing at a partially written file.
  store(bytes,verified.packageSha256());
  try{jdbc.sql("INSERT INTO pf_module_package(module_code,package_version,sha256,manifest_json,status,uploaded_by,created_at) VALUES(:code,:v,:sha,:manifest,'VERIFIED',:actor,:now)")
   .param("code",code).param("v",version).param("sha",verified.packageSha256()).param("manifest",manifestJson(bytes))
   .param("actor",actor.userId()).param("now",Timestamp.from(Instant.now())).update();
  }catch(DuplicateKeyException e){throw new PlatformApiException(ErrorCode.MODULE_VERSION_CONFLICT,"该模块版本已收录，不允许覆盖");}
  audit.record(actor.userId(),actor.publicId(),"MODULE_PACKAGE_VERIFIED","MODULE",code,"{}",trace);
  return list().stream().filter(v->v.moduleCode().equals(code)&&v.version().equals(version)).findFirst().orElseThrow();
 }
 private String manifestJson(byte[] archive){try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive))){java.util.zip.ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().equals("manifest.json"))return new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);throw new IllegalArgumentException("缺少清单");}catch(IOException e){throw new IllegalArgumentException("模块包读取失败",e);}}
 private void store(byte[] bytes,String sha){
  try{Files.createDirectories(root);Path target=root.resolve(sha+".caremod");if(Files.exists(target)){if(!ModulePackageVerifier.sha256(Files.readAllBytes(target)).equals(sha))throw new IllegalStateException("模块存储校验失败");return;}
   Path temp=Files.createTempFile(root,"upload-",".tmp");try{Files.write(temp,bytes);try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){throw new IllegalStateException("模块存储必须支持原子移动",e);}}finally{Files.deleteIfExists(temp);}
  }catch(IOException e){throw new IllegalStateException("模块包存储失败",e);}
 }
 public List<PackageView> list(){return jdbc.sql("SELECT p.module_code,m.name,p.package_version,p.sha256,p.status,p.created_at FROM pf_module_package p JOIN pf_module_definition m ON m.code=p.module_code ORDER BY p.created_at DESC,p.id DESC").query((r,n)->new PackageView(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant())).list();}
 public byte[] download(String code,String version){String sha=jdbc.sql("SELECT sha256 FROM pf_module_package WHERE module_code=:code AND package_version=:v").param("code",code).param("v",version).query(String.class).optional().orElseThrow(()->new PlatformApiException(ErrorCode.NOT_FOUND,"模块包不存在"));try{byte[] bytes=Files.readAllBytes(root.resolve(sha+".caremod"));if(!ModulePackageVerifier.sha256(bytes).equals(sha))throw new IllegalStateException("模块包存储摘要不符");verifier().verify(bytes);return bytes;}catch(IOException e){throw new IllegalStateException("无法读取模块包",e);}}
 public record PackageView(String moduleCode,String name,String version,String sha256,String status,Instant createdAt){}
 @Transactional public void publish(String code,String version,PlatformPrincipal actor,String trace){
  download(code,version);
  int changed=jdbc.sql("UPDATE pf_module_package SET status='PUBLISHED' WHERE module_code=:code AND package_version=:v AND status='VERIFIED'").param("code",code).param("v",version).update();
  if(changed!=1)throw new PlatformApiException(ErrorCode.MODULE_VERSION_CONFLICT,"模块版本不存在或已经发布");
  audit.record(actor.userId(),actor.publicId(),"MODULE_PACKAGE_PUBLISHED","MODULE",code,"{}",trace);
 }
 public List<PackageView> available(long enterprise){
  var now=Timestamp.from(Instant.now());
  return list().stream().filter(p->!"hello-module".equals(p.moduleCode())).filter(p->"PUBLISHED".equals(p.status())&&jdbc.sql("SELECT COUNT(*) FROM pf_entitlement e JOIN pf_module_definition m ON m.id=e.module_id WHERE e.enterprise_id=:enterprise AND m.code=:code AND e.status='ACTIVE' AND e.starts_at<=:now AND (e.perpetual=TRUE OR e.expires_at>:now)").param("enterprise",enterprise).param("code",p.moduleCode()).param("now",now).query(Long.class).single()>0).toList();
 }
 public byte[] downloadForEnterprise(long enterprise,String code,String version){
  if(available(enterprise).stream().noneMatch(p->p.moduleCode().equals(code)&&p.version().equals(version)))throw new PlatformApiException(ErrorCode.PERMISSION_DENIED,"模块未发布或企业没有有效授权");
  return download(code,version);
 }
}
