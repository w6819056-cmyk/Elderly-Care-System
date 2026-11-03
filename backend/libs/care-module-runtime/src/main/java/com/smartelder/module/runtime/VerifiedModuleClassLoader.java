package com.smartelder.module.runtime;
import java.io.*;
import java.net.*;
import java.util.*;

/** Definitions come exclusively from immutable verified byte snapshots. */
final class VerifiedModuleClassLoader extends ClassLoader implements AutoCloseable {
 private final Map<String,byte[]> resources;
 VerifiedModuleClassLoader(Map<String,byte[]> resources,ClassLoader parent){super(parent);this.resources=new HashMap<>();resources.forEach((k,v)->this.resources.put(k,v.clone()));}
 @Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] v=resources.get(name.replace('.','/')+".class");if(v==null)throw new ClassNotFoundException(name);return defineClass(name,v,0,v.length);}
 @Override protected URL findResource(String name){byte[] v=resources.get(name);if(v==null)return null;try{return new URL(null,"care-module:/"+name,new URLStreamHandler(){protected URLConnection openConnection(URL u){return new URLConnection(u){public void connect(){}public InputStream getInputStream(){return new ByteArrayInputStream(v);}};}});}catch(MalformedURLException e){throw new IllegalStateException(e);}}
 @Override protected Enumeration<URL> findResources(String name){URL v=findResource(name);return Collections.enumeration(v==null?List.of():List.of(v));}
 @Override public void close(){resources.clear();}
}
