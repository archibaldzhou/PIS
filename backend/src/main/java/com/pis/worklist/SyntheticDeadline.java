package com.pis.worklist;
import java.time.Instant;
/** Deliberately no clinical/business-calendar interpretation. Equality is not overdue. */
public final class SyntheticDeadline {
 private SyntheticDeadline() { }
 public static Instant dueAt(Instant created,int minutes) {
  if(minutes<1||minutes>10080) throw new IllegalArgumentException("Synthetic threshold must be 1..10080 minutes");
  return created.plusSeconds(minutes*60L);
 }
 public static boolean overdue(Instant created,int minutes,Instant asOf,boolean active) { return active && asOf.isAfter(dueAt(created,minutes)); }
}
