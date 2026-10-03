package com.pis.scan;
import java.time.Instant;
public final class ScanLeasePolicy {
 private ScanLeasePolicy(){}public static final int MAX_ATTEMPTS=3;
 public static boolean expired(Instant deadline,Instant now){return deadline==null||!deadline.isAfter(now);}
 public static Instant deadline(Instant now){return now.plusSeconds(30);}
 public static Instant retryAt(Instant now,int attempts){if(attempts<1||attempts>MAX_ATTEMPTS)throw new IllegalArgumentException("Bounded synthetic attempt required");return now.plusSeconds(5L*attempts);}
}
