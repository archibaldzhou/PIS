package com.pis.statistics;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
public final class StatisticsPolicy {
 private StatisticsPolicy() { }
 public static final String DEFINITION="SYN-STATS-1";
 public record Window(Instant start,Instant end) { }
 public static Window window(LocalDate from,LocalDate to,String zone) {if(from==null||to==null||zone==null||to.isBefore(from)||ChronoUnit.DAYS.between(from,to)>=92)throw new IllegalArgumentException("Invalid bounded dates");if(!ZoneId.getAvailableZoneIds().contains(zone))throw new IllegalArgumentException("Named timezone required");var z=ZoneId.of(zone);return new Window(from.atStartOfDay(z).toInstant(),to.plusDays(1).atStartOfDay(z).toInstant());}
 public record Timing(String status,Long seconds) { }
 public static Timing timing(Instant start,Instant end,Instant cutoff,boolean excluded){if(excluded)return new Timing("EXCLUDED",null);if(start==null||start.isAfter(cutoff)||end!=null&&(end.isBefore(start)||end.isAfter(cutoff)))return new Timing("UNKNOWN",null);return new Timing(end==null?"OPEN":"COMPLETED",Duration.between(start,end==null?cutoff:end).getSeconds());}
 public static Double median(List<Long> values){if(values.isEmpty())return null;var a=values.stream().sorted().toList();int n=a.size();return n%2==1?a.get(n/2).doubleValue():a.get(n/2-1)/2.0+a.get(n/2)/2.0;}
}
