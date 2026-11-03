package com.smartelder.installer;
import com.smartelder.module.runtime.ModuleRuntimeFiles;
import com.smartelder.module.packaging.ModulePackageManifest;
import java.nio.file.Path;

/** Commands are operator-owned deployment configuration, never module payload commands. */
public interface InstallationHost {
 default void preflight()throws Exception{}
 void stop()throws Exception;
 Backup backup(String planId)throws Exception;
 void migrate(com.smartelder.module.packaging.VerifiedModulePackage module,Path payload)throws Exception;
 void start()throws Exception;
 void awaitReady(ModuleRuntimeFiles.Index expected)throws Exception;
 record Backup(String path,String sha256){}
}
