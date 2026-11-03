package com.smartelder.api.tenant.hq;

import com.smartelder.core.iam.TenantIamService.StaffView;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StaffProfileService {
 private final JdbcClient jdbc;
 private final Path root;
 public StaffProfileService(JdbcClient jdbc,@Value("${app.staff-avatar-directory:./data/staff-avatars}") String directory){this.jdbc=jdbc;root=Path.of(directory).toAbsolutePath().normalize();}
 public byte[] validateAvatar(String data){
  if(data==null||data.isBlank())return null;
  if(data.length()>2800000)throw new IllegalArgumentException("头像不得超过 2MB");
  String prefix=data.startsWith("data:image/png;base64,")?"data:image/png;base64,":data.startsWith("data:image/jpeg;base64,")?"data:image/jpeg;base64,":null;
  if(prefix==null)throw new IllegalArgumentException("头像仅支持 JPG、PNG");
  try{byte[] bytes=Base64.getDecoder().decode(data.substring(prefix.length()));if(bytes.length>2097152)throw new IllegalArgumentException("头像不得超过 2MB");
   try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IllegalArgumentException("无效头像图片");var reader=readers.next();try{reader.setInput(stream);String format=reader.getFormatName();if(!format.equalsIgnoreCase("PNG")&&!format.equalsIgnoreCase("JPEG"))throw new IllegalArgumentException("头像仅支持 JPG、PNG");int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>4096||h>4096||(long)w*h>16000000)throw new IllegalArgumentException("头像尺寸不得超过 4096 像素");BufferedImage source=reader.read(0);int side=Math.min(w,h);BufferedImage image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,256,256);g.drawImage(source,0,0,256,256,(w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2,null);}finally{g.dispose();}var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}finally{reader.dispose();}}
  }catch(IOException e){throw new IllegalArgumentException("无法读取头像图片",e);}
 }
 public void save(String publicId,String email,byte[] avatar){
  String key=null;
  try{if(avatar!=null){Files.createDirectories(root);key=UUID.randomUUID()+".png";Files.write(root.resolve(key),avatar,StandardOpenOption.CREATE_NEW);}
   jdbc.sql("UPDATE iam_staff SET email=:email,avatar_key=:key WHERE public_id=:id").param("email",email==null||email.isBlank()?null:email.trim()).param("key",key).param("id",publicId).update();
  }catch(IOException e){throw new IllegalStateException("头像保存失败，请联系企业管理员",e);}
 }
 public ProfileStaff view(StaffView s){var profile=jdbc.sql("SELECT phone,email,avatar_key FROM iam_staff WHERE public_id=:id").param("id",s.publicId()).query((rs,n)->new String[]{rs.getString("phone"),rs.getString("email"),rs.getString("avatar_key")}).single();return new ProfileStaff(s.id(),s.publicId(),s.username(),s.displayName(),s.staffType(),s.homeStoreId(),s.status(),s.version(),profile[0],profile[1],profile[2]!=null);}
 public byte[] avatar(String publicId){String key=jdbc.sql("SELECT avatar_key FROM iam_staff WHERE public_id=:id").param("id",publicId).query((rs,n)->rs.getString(1)).optional().orElse(null);if(key==null)return null;Path file=root.resolve(key).normalize();if(!file.getParent().equals(root))throw new IllegalStateException("Invalid avatar path");try{return Files.readAllBytes(file);}catch(IOException e){return null;}}
 public record ProfileStaff(long id,String publicId,String username,String displayName,String staffType,Long homeStoreId,String status,long version,String phone,String email,boolean hasAvatar){}
}
