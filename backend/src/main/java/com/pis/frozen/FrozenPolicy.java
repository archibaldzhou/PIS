package com.pis.frozen;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
/** Pure development rules, never clinical advice or a hospital TAT. */
public final class FrozenPolicy {
 private FrozenPolicy() { }
 public static Instant time(OffsetDateTime value,String zone,Instant now) {
  if(value==null||zone==null||value.getYear()<1||value.getYear()>9999||!ZoneId.getAvailableZoneIds().contains(zone))throw new IllegalArgumentException("FROZEN_TIME_INVALID");
  try {
   var id=ZoneId.of(zone);
   if(!id.getRules().getOffset(value.toInstant()).equals(value.getOffset())||value.toInstant().isAfter(now))throw new IllegalArgumentException("FROZEN_TIME_INVALID");
   return value.toInstant();
  } catch(DateTimeException e){throw new IllegalArgumentException("FROZEN_TIME_INVALID");}
 }
 public static void after(Instant value,Instant predecessor) {
  if(predecessor==null||value.isBefore(predecessor))throw new IllegalArgumentException("FROZEN_TIME_ORDER");
 }
 public static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
