package com.pis.storage;
import java.io.*;import java.util.*;
/** Only server-generated UUIDs cross the storage boundary; no paths or URLs. */
public interface StorageProvider extends AutoCloseable {
 record Capacity(String state,UUID rootId,Long volumeTotal,Long volumeUsable) { }
 record Slice(byte[] bytes,long start,long end,long total) { }
 String state(); UUID rootId();
 void stage(UUID id,InputStream input,long size,String sha256) throws IOException;
 void verifyStage(UUID id,long size,String sha256) throws IOException;
 void publish(UUID id,long size,String sha256) throws IOException;
 Slice read(UUID id,long size,String sha256,String range) throws IOException;
 Capacity capacity() throws IOException;
 boolean hasStage(UUID id) throws IOException;
 @Override void close() throws IOException;
 final class Failure extends IOException { public final String code; public Failure(String code){super(code);this.code=code;} }
}
