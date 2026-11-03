package com.smartelder.core.installation;

import com.smartelder.module.packaging.ModulePackageManifest;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.simple.JdbcClient;


/** Installation expands executable capabilities; wind-down and read-only licenses cannot install. */

public class InstallLicensePolicy {
 private final JdbcClient jdbc;private final Clock clock;
 public InstallLicensePolicy(JdbcClient jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
 public void check(ModulePackageManifest m){
  Instant now=clock.instant();var snapshot=jdbc.sql("SELECT platform_instance_status,last_successful_sync_at,offline_grace_days,last_platform_time,not_before,license_expires_at FROM lic_license_snapshot WHERE id=1").query((r,n)->new Snapshot(r.getString(1),r.getTimestamp(2).toInstant(),r.getInt(3),r.getTimestamp(4).toInstant(),r.getTimestamp(5).toInstant(),r.getTimestamp(6)==null?null:r.getTimestamp(6).toInstant())).optional().orElseThrow(()->new IllegalStateException("尚未获取有效企业授权，请先激活并同步授权"));
  if(!snapshot.status().equals("ACTIVE")||now.isBefore(snapshot.notBefore())||now.isBefore(snapshot.platformTime().minusSeconds(300))||!now.isBefore(snapshot.lastSync().plus(Duration.ofDays(snapshot.offlineDays())))||(snapshot.expires()!=null&&!now.isBefore(snapshot.expires())))throw new IllegalStateException("企业授权当前不允许安装，请联网同步授权");
  grant(m.module().code(),false,now);
  for(var d:m.module().dependencies()){
   grant(d.moduleCode(),true,now);
   String installed=jdbc.sql("SELECT package_version FROM core_module_install_plan WHERE module_code=:code AND status='ACTIVE' ORDER BY updated_at DESC").param("code",d.moduleCode()).query(String.class).list().stream().findFirst().orElseThrow(()->new IllegalStateException("依赖模块尚未实际安装："+d.moduleCode()));
   if(compare(installed,d.minimumVersion())<0)throw new IllegalStateException("依赖模块版本不足："+d.moduleCode());
  }
 }
 private void grant(String code,boolean formal,Instant now){long count=jdbc.sql("SELECT COUNT(*) FROM lic_module_entitlement WHERE module_code=:code AND status='ACTIVE' AND starts_at<=:now AND (perpetual=TRUE OR expires_at>:now)"+(formal?" AND license_type='FORMAL'":"")).param("code",code).param("now",Timestamp.from(now)).query(Long.class).single();if(count!=1)throw new IllegalStateException(formal?"依赖模块必须拥有有效正式授权："+code:"模块尚未获得有效授权或已到期："+code);}
 private static int compare(String a,String b){String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int c=new java.math.BigInteger(i<x.length?x[i]:"0").compareTo(new java.math.BigInteger(i<y.length?y[i]:"0"));if(c!=0)return c;}return 0;}
 private record Snapshot(String status,Instant lastSync,int offlineDays,Instant platformTime,Instant notBefore,Instant expires){}
}
