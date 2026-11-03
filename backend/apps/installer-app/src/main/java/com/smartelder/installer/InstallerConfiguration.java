package com.smartelder.installer;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Read from an operator-owned JSON file outside the repository. Never accepted from HTTP. */
public record InstallerConfiguration(String jdbcUrl,String username,String passwordEnvironment,
 String runtimeDirectory,String cacheDirectory,String trustDirectory,String coreVersion,
 List<String> stopCommand,List<String> startCommand,String workingDirectory,String dumpExecutable,
 String healthUrl,int timeoutSeconds) {
 public InstallerConfiguration {
  if(jdbcUrl==null||!jdbcUrl.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("Production installer requires a MySQL JDBC URL");
  if(username==null||username.isBlank()||passwordEnvironment==null||!passwordEnvironment.matches("[A-Z][A-Z0-9_]{0,100}"))throw new IllegalArgumentException("Missing database identity/password environment name");
  for(String p:List.of(runtimeDirectory,cacheDirectory,trustDirectory,workingDirectory,dumpExecutable))if(!Path.of(p).isAbsolute())throw new IllegalArgumentException("Installer paths must be absolute");
  stopCommand=List.copyOf(stopCommand);startCommand=List.copyOf(startCommand);
  for(var command:List.of(stopCommand,startCommand))if(command.isEmpty()||!Path.of(command.get(0)).isAbsolute()||command.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Lifecycle commands must be explicit argument arrays with absolute executables");
  URI health=URI.create(healthUrl);
  if(!Set.of("http","https").contains(health.getScheme())||!Set.of("localhost","127.0.0.1","[::1]","::1").contains(health.getHost())||health.getUserInfo()!=null||health.getFragment()!=null)throw new IllegalArgumentException("Health URL must be on the local machine");
  if(!"/api/public/runtime-health".equals(health.getPath())||health.getQuery()!=null)throw new IllegalArgumentException("Installer requires the startup-bound runtime health endpoint");
  if(timeoutSeconds<10||timeoutSeconds>600)throw new IllegalArgumentException("Timeout must be 10..600 seconds");
  if(coreVersion==null||!coreVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("Invalid core version");
 }
 public void validateFiles()throws Exception {
  for(String p:List.of(runtimeDirectory,cacheDirectory,trustDirectory,workingDirectory,dumpExecutable,stopCommand.get(0),startCommand.get(0)))com.smartelder.module.runtime.ModuleRuntimeFiles.noSymlinks(Path.of(p));
  if(!Files.isDirectory(Path.of(workingDirectory))||!Files.isDirectory(Path.of(cacheDirectory))||!Files.isDirectory(Path.of(trustDirectory)))throw new IllegalArgumentException("Installer directories are not configured");
  for(String p:List.of(dumpExecutable,stopCommand.get(0),startCommand.get(0)))if(!Files.isRegularFile(Path.of(p)))throw new IllegalArgumentException("Lifecycle or backup executable missing");
 }
}
