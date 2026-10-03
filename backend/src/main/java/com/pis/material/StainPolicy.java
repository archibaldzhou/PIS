package com.pis.material;
import java.time.LocalDate;
public final class StainPolicy {
 private StainPolicy() { }
 public static void expiry(LocalDate expires,LocalDate today){if(expires==null||expires.getYear()<1||expires.getYear()>9999||expires.isBefore(today))throw new IllegalArgumentException("STAIN_EXPIRED");}
 public static void quantity(int count,int unique,int existing){if(count<1||count>20||count!=unique||count+existing>20)throw new IllegalArgumentException("STAIN_QUANTITY");}
 public static boolean code(String value){return value!=null&&value.matches("SYN-[A-Z0-9-]{1,40}");}
}
