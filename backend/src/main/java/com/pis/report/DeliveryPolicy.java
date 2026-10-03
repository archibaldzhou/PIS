package com.pis.report;
import java.time.Instant;
import java.util.UUID;
/** Bounded local worker policy; no timers, network or clinical decisions. */
public final class DeliveryPolicy {
 private DeliveryPolicy() { }
 public static boolean canClaim(String state,int attempts,Instant next,Instant now) { return (state.equals("QUEUED")||state.equals("RETRY_WAIT"))&&attempts>=0&&attempts<3&&!now.isBefore(next); }
 public static boolean active(String state,UUID current,UUID supplied,Instant lease,Instant now) { return state.equals("ATTEMPTING")&&current!=null&&current.equals(supplied)&&lease!=null&&now.isBefore(lease); }
 public static long backoffSeconds(int attempts) { if(attempts<1||attempts>3)throw new IllegalArgumentException("Invalid attempt count");return attempts==1?5:20; }
 public static String failureState(int attempts,boolean poison) { if(attempts<1||attempts>3)throw new IllegalArgumentException("Invalid attempt count");return poison||attempts==3?"DEAD":"RETRY_WAIT"; }
}
