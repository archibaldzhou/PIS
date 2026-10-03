package com.pis.material;
/** Counts are synthetic portions, not cell counts, volume conversions or clinical yield. */
public final class CytologyPolicy {
 private CytologyPolicy() { }
 public static void reserve(int remaining,int transfer){if(transfer<1||transfer>99||transfer>remaining)throw new IllegalArgumentException("CYTOLOGY_QUANTITY");}
 public static void reconcile(int transferred,int consumed,int discarded,int returned,int slides,boolean failed){
  if(transferred<1||transferred>99||consumed<0||discarded<0||returned<0||consumed>99||discarded>99||returned>99||consumed+discarded+returned!=transferred||slides<0||slides>20||(failed?(consumed!=0||slides!=0):(slides<1||slides>consumed)))throw new IllegalArgumentException("CYTOLOGY_RECONCILIATION");
 }
}
