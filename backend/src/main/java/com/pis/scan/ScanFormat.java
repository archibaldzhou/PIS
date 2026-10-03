package com.pis.scan;
import java.nio.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;
/** Bounded synthetic header contract only. No image decoder, vendor inference or external I/O. */
public final class ScanFormat {
 private ScanFormat(){}
 public static final byte[] TRANSPORT="PIS-SYNTHETIC-STORAGE-V1\n".getBytes(StandardCharsets.US_ASCII);
 private static final byte[] MAGIC="PISSCN1\n".getBytes(StandardCharsets.US_ASCII);
 public record Result(String code,UUID patientId,UUID caseId,UUID slideId,String barcode,int width,int height){}
 public static String sha(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static Result error(String code){return new Result(code,null,null,null,"",0,0);}
 public static Result inspect(byte[] bytes){
  if(bytes.length>512)return error("INPUT_LIMIT");int offset=Arrays.equals(Arrays.copyOf(bytes,Math.min(bytes.length,TRANSPORT.length)),TRANSPORT)?TRANSPORT.length:0;
  byte[] b=Arrays.copyOfRange(bytes,offset,bytes.length);
  if(b.length>=4&&((b[0]=='I'&&b[1]=='I'&&(b[2]==42||b[2]==43)&&b[3]==0)||(b[0]=='M'&&b[1]=='M'&&b[2]==0&&(b[3]==42||b[3]==43))))return error("FORMAT_NOT_CONFIGURED");
  if(b.length>=4&&b[0]=='P'&&b[1]=='K')return error("UNSUPPORTED_ARCHIVE");
  boolean rgb=b.length>=8&&Arrays.equals(Arrays.copyOf(b,8),"PISRGB1\n".getBytes(StandardCharsets.US_ASCII));
  if(b.length<8||(!rgb&&!Arrays.equals(Arrays.copyOf(b,8),MAGIC)))return error("UNSUPPORTED_FORMAT");
  if(offset==0||b.length<98)return error("CORRUPT_SYNTHETIC");
  var in=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);in.position(8);UUID patient=new UUID(in.getLong(),in.getLong()),caseId=new UUID(in.getLong(),in.getLong()),slide=new UUID(in.getLong(),in.getLong());byte[] label=new byte[34];in.get(label);String barcode=new String(label,StandardCharsets.US_ASCII);int width=in.getInt(),height=in.getInt();
  if(!com.pis.label.LabelBarcode.valid(barcode)||width<1||height<1||width>4096||height>4096)return error("CORRUPT_SYNTHETIC");if(rgb&&(width>512||height>512))return error("CORRUPT_SYNTHETIC");return new Result(rgb?"SYNTHETIC_RGB_ONLY":"SYNTHETIC_HEADER_ONLY",patient,caseId,slide,barcode,width,height);
 }
 public static byte[] fixture(UUID patient,UUID caseId,UUID slide,String barcode,int width,int height){if(!com.pis.label.LabelBarcode.valid(barcode))throw new IllegalArgumentException("Synthetic slide barcode required");var b=ByteBuffer.allocate(256);b.put(TRANSPORT).put(MAGIC);for(var id:List.of(patient,caseId,slide))b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());b.put(barcode.getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);return b.array();}
}
