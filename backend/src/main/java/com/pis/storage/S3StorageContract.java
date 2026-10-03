package com.pis.storage;
import java.io.*;import java.util.UUID;
/** Contract-only placeholder. No endpoint, SDK, transport or credential handling exists. */
public final class S3StorageContract implements StorageProvider {
 public String state(){return "NOT_CONFIGURED";} public UUID rootId(){return null;}
 public void stage(UUID id,InputStream input,long size,String hash)throws IOException{throw missing();}
 public void verifyStage(UUID id,long size,String hash)throws IOException{throw missing();}
 public void publish(UUID id,long size,String hash)throws IOException{throw missing();}
 public Slice read(UUID id,long size,String hash,String range)throws IOException{throw missing();}
 public Capacity capacity(){return new Capacity(state(),null,null,null);}
 public boolean hasStage(UUID id)throws IOException{throw missing();} public void close(){}
 private static Failure missing(){return new Failure("STORAGE_NOT_CONFIGURED");}
}
