package com.pis.archive;
import java.time.Instant;
public final class ArchivePolicy {
 private ArchivePolicy() { }
 public static boolean validDue(Instant due,Instant now,int days) { return days>=1&&days<=365&&due!=null&&due.isAfter(now)&&!due.isAfter(now.plusSeconds(days*86400L)); }
 public static boolean lendable(String condition,String sourceStatus) { return "RECORDED".equals(condition)&&("PASS".equals(sourceStatus)||"IMMUTABLE_ARTIFACT".equals(sourceStatus)); }
 public static String condition(String current,String action) {
  return switch(action) { case "DAMAGE" -> current.equals("RECORDED")||current.equals("FOUND_PENDING")?"DAMAGED":null; case "LOST" -> !current.equals("LOST")?"LOST":null; case "FOUND" -> current.equals("LOST")?"FOUND_PENDING":null; default -> null; };
 }
}
