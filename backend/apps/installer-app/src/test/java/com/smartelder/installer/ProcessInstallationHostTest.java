package com.smartelder.installer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.module.runtime.ModuleRuntimeFiles;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProcessInstallationHostTest {
 @TempDir Path directory;
 String java(){return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();}
 InstallerConfiguration configuration(int port){return new InstallerConfiguration("jdbc:mysql://127.0.0.1:3306/test","test","CARE_TEST_PASSWORD",directory.toString(),directory.toString(),directory.toString(),"2.0.0",List.of(java()),List.of(java()),directory.toString(),java(),"http://127.0.0.1:"+port+"/api/public/runtime-health",10);}
 @Test void stopRetainsPreflightProcessIdentityIfReceiptDisappears()throws Exception{
  Process child=new ProcessBuilder(java(),"-cp",System.getProperty("java.class.path"),Sleeping.class.getName()).redirectErrorStream(true).start();
  try{
   assertThat(new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine()).isEqualTo("READY");
   ModuleRuntimeFiles.atomicJson(directory.resolve("runtime-ready.json"),Map.of("pid",child.pid(),"processStartedAt",child.info().startInstant().orElseThrow().toString()));
   var files=new ModuleRuntimeFiles(directory,directory,directory);var host=new ProcessInstallationHost(configuration(8082),mock(DataSource.class),"secret",files){protected void command(List<String> args,String action)throws Exception{if(action.equals("stop")){child.destroyForcibly();child.waitFor();}}};
   host.preflight();Files.delete(directory.resolve("runtime-ready.json"));host.stop();assertThat(child.isAlive()).isFalse();
  }finally{child.destroyForcibly();}
 }
 @Test void healthMustMatchStartupNonceProcessStartTimeAndPort()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String nonce=UUID.randomUUID().toString();long pid=ProcessHandle.current().pid();String started=ProcessHandle.current().info().startInstant().orElseThrow().toString();
  var identity=new HashMap<String,Object>(Map.of("startupId",nonce,"pid",pid,"processStartedAt",started));
  server.createContext("/api/public/runtime-health",exchange->{byte[] bytes=new ObjectMapper().writeValueAsBytes(identity);exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}});server.start();
  try{
   int port=server.getAddress().getPort();ModuleRuntimeFiles.atomicJson(directory.resolve("runtime-ready.json"),Map.of("startupId",nonce,"pid",pid,"processStartedAt",started,"port",port,"modules",Map.of()));
   var host=new ProcessInstallationHost(configuration(port),mock(DataSource.class),"secret",new ModuleRuntimeFiles(directory,directory,directory));host.awaitReady(new ModuleRuntimeFiles.Index(1,Map.of()));
   identity.put("startupId","unrelated-service-startup");assertThatThrownBy(()->host.awaitReady(new ModuleRuntimeFiles.Index(1,Map.of()))).hasMessageContaining("did not match");
  }finally{server.stop(0);}
 }
 public static class Sleeping {public static void main(String[] args)throws Exception{System.out.println("READY");System.out.flush();Thread.sleep(60000);}}
}
