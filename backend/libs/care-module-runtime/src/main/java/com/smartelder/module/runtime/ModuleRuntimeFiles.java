package com.smartelder.module.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.module.packaging.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** All runtime bytes are tied to a verified signed archive, never arbitrary extraction paths. */
public final class ModuleRuntimeFiles {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path root,cache,trust;
    public ModuleRuntimeFiles(Path root,Path cache,Path trust){this.root=root.toAbsolutePath().normalize();this.cache=cache.toAbsolutePath().normalize();this.trust=trust.toAbsolutePath().normalize();}
    public Path root(){return root;}
    public static void noSymlinks(Path path) throws IOException {
        for(Path p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Runtime paths must not contain symbolic links");
    }
    public VerifiedModulePackage verify(String sha) throws IOException {
        if(sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid package digest");
        Path archive=cache.resolve(sha+".caremod");noSymlinks(archive);
        if(Files.size(archive)>ModulePackageVerifier.MAX_ARCHIVE_BYTES)throw new IOException("Package too large");
        byte[] bytes=Files.readAllBytes(archive);
        if(!ModulePackageVerifier.sha256(bytes).equals(sha))throw new IOException("Cached package digest changed");
        return new ModulePackageVerifier(TrustedPublisherKeys.load(trust)).verify(bytes);
    }
    public Path payload(String sha){if(sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid digest");return root.resolve("payloads").resolve(sha);}
    public Path extract(VerifiedModulePackage verified) throws IOException {
        noSymlinks(root);Path target=payload(verified.packageSha256());
        if(Files.exists(target)){verifyPayload(target,verified);return target;}
        Files.createDirectories(target.getParent());Path staging=Files.createTempDirectory(target.getParent(),"stage-");
        try {
            for(String name:verified.paths()) {
                Path output=staging.resolve(name).normalize();if(!output.startsWith(staging))throw new IOException("Unsafe module path");
                Files.createDirectories(output.getParent());Files.write(output,verified.file(name),StandardOpenOption.CREATE_NEW);
            }
            Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);return target;
        } finally {
            if(Files.exists(staging)){try(var walk=Files.walk(staging)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        }
    }
    public void verifyPayload(Path directory,VerifiedModulePackage verified) throws IOException {
        noSymlinks(directory);Set<String> actual=new HashSet<>();
        try(var walk=Files.walk(directory)) {for(Path p:walk.toList()) {
            noSymlinks(p);if(Files.isDirectory(p))continue;
            String relative=directory.relativize(p).toString().replace('\\','/');
            if(!verified.paths().contains(relative)||Files.size(p)>32*1024*1024||!ModulePackageVerifier.sha256(Files.readAllBytes(p)).equals(verified.manifest().files().get(relative)))throw new IOException("Extracted module file changed: "+relative);
            actual.add(relative);
        }}
        if(!actual.equals(verified.paths()))throw new IOException("Extracted module files missing");
    }
    public Index index() throws IOException {
        Path p=root.resolve("active-index.json");noSymlinks(p);
        if(!Files.exists(p))return new Index(1,Map.of());
        if(Files.size(p)>1024*1024)throw new IOException("Runtime index too large");
        return JSON.readValue(Files.readAllBytes(p),Index.class);
    }
    public void index(Index index) throws IOException {atomicJson(root.resolve("active-index.json"),index);}
    public static void atomicJson(Path target,Object value) throws IOException {
        noSymlinks(target);Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"write-",".tmp");
        try {Files.write(temp,JSON.writeValueAsBytes(value));Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    public record Entry(String planId,String sha256) {
        public Entry {UUID.fromString(planId);if(sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid digest");}
    }
    public record Index(int formatVersion,Map<String,Entry> modules) {
        public Index {if(formatVersion!=1)throw new IllegalArgumentException("Unsupported runtime index");modules=Map.copyOf(modules);for(String code:modules.keySet())if(!code.matches("[a-z][a-z0-9-]{1,31}"))throw new IllegalArgumentException("Invalid module code");}
    }
}
