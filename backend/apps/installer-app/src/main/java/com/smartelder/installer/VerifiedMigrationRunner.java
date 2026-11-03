package com.smartelder.installer;
import com.smartelder.module.packaging.VerifiedModulePackage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Flyway reads only verified in-memory SQL, not mutable extracted filesystem paths. */
public final class VerifiedMigrationRunner {
 private VerifiedMigrationRunner(){}
 public static void migrate(DataSource source,VerifiedModulePackage verified){
  var manifest=verified.manifest();if(manifest.migrations().isEmpty())return;
  Map<String,LoadableResource> scripts=new LinkedHashMap<>();
  for(String path:manifest.migrations()){String name=path.substring(path.lastIndexOf('/')+1);byte[] bytes=verified.file(path);scripts.put(name,new LoadableResource(){
   public Reader read(){return new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8);}
   public String getAbsolutePath(){return "caremod:"+verified.packageSha256()+"/"+path;}
   public String getAbsolutePathOnDisk(){return null;}
   public String getFilename(){return name;}
   public String getRelativePath(){return name;}
  });}
  ResourceProvider provider=new ResourceProvider(){
   public LoadableResource getResource(String name){return scripts.get(name);}
   public Collection<LoadableResource> getResources(String prefix,String[] suffixes){return scripts.values().stream().filter(r->r.getFilename().startsWith(prefix)&&Arrays.stream(suffixes).anyMatch(s->r.getFilename().endsWith(s))).toList();}
  };
  Flyway.configure().dataSource(source).resourceProvider(provider).locations("classpath:care-module-verified-only")
   .table("flyway_module_"+manifest.module().code().replace('-','_')).baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).validateOnMigrate(true).outOfOrder(false).load().migrate();
 }
}
