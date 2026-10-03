package com.pis.report;
import java.time.*;
public final class ConsultationPolicy {
 private ConsultationPolicy() { }
 public static void expiry(Instant until,Instant now){if(until==null||!until.isAfter(now)||until.isAfter(now.plus(Duration.ofDays(30))))throw new IllegalArgumentException("CONSULT_EXPIRY");}
 public static boolean opinion(String value){return value!=null&&java.util.Set.of("AGREE","DISAGREE","UNKNOWN").contains(value);}
 public static boolean summary(String value){return value!=null&&java.util.Set.of("RESOLVED","UNRESOLVED").contains(value);}
}
