package com.pis.storage;
import java.nio.charset.StandardCharsets;
public final class StoragePolicy {
 private StoragePolicy(){} public static final long MAX_SIZE=64L*1024*1024,SLICE=1024*1024,QUOTA=512L*1024*1024;
 public static final byte[] MARKER="PIS-SYNTHETIC-STORAGE-V1\n".getBytes(StandardCharsets.US_ASCII);
 public record Range(long start,long end){public int length(){return Math.toIntExact(end-start+1);}}
 public static void size(long n)throws StorageProvider.Failure{if(n<Math.max(25,MARKER.length)||n>MAX_SIZE)throw new StorageProvider.Failure("STORAGE_SIZE");}
 public static Range range(String input,long total)throws StorageProvider.Failure{
  size(total);if(input==null){if(total>SLICE)throw new StorageProvider.Failure("STORAGE_RANGE");return new Range(0,total-1);}
  if(!input.matches("bytes=[0-9]{1,10}-[0-9]{1,10}"))throw new StorageProvider.Failure("STORAGE_RANGE");
  var a=input.substring(6).split("-");long start=Long.parseLong(a[0]),end=Long.parseLong(a[1]);if(start>end||end>=total||end-start+1>SLICE)throw new StorageProvider.Failure("STORAGE_RANGE");return new Range(start,end);
 }
}
