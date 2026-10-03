package com.pis.statistics;
import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;
public final class StatisticsContracts {
 private StatisticsContracts() { }
 public enum Metric { RECEPTION,TECHNICAL,REPORT,QC }
 public enum Sort { ENTITY,START_ASC,START_DESC }
 public record Query(@NotNull LocalDate from,@NotNull LocalDate to,@NotBlank @Size(max=64) String zone) { }
 public record Fact(Metric metric,UUID entityId,UUID requestId,UUID startEvent,UUID endEvent,Instant startAt,Instant endAt,String status,Long durationSeconds,String sourceBasis) { }
 public record Summary(Metric metric,String availability,long cohort,long completed,long open,long unknown,long excluded,long numerator,long denominator,Double ratio,Double medianSeconds) { }
 public record View(UUID id,UUID scopeId,String definition,LocalDate from,LocalDate to,String zone,Instant cutoff,String factsHash,boolean canDrill,List<Summary> summaries,Metric metric,Sort sort,int page,long total,List<Fact> facts) { }
}
