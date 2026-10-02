package com.pis.worklist;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class WorklistContracts {
 private WorklistContracts() { }
 public enum Kind { ALL, REQUEST, RECEPTION, TECHNICAL, QUALITY }
 public enum State { ALL,DRAFT,SUBMITTED,RECEIVED,EXCEPTION,RETURNED,QUEUED,ACTIVE,HANDOFF_PENDING,SIMULATED_DONE,ABORTED,NOT_ASSESSED,PASS,FAIL,PENDING,IDENTITY_MISMATCH,REVOKED,REWORK_REQUIRED,INVALIDATED,SOURCE_QUARANTINED }
 public enum Sort { OLDEST, NEWEST, NUMBER }
 public enum Due { ALL, OVERDUE, NOT_OVERDUE }
 public record Item(String kind,UUID id,UUID requestId,UUID patientId,String requestNumber,String state,long version,Instant createdAt,UUID cassetteId,boolean blocked,boolean active,Instant dueAt,boolean overdue) { }
 public record Page(long total,int page,int pageSize,Instant asOf,int syntheticDueMinutes,List<Item> items) { }
 public record TraceEvent(UUID eventId,String domain,UUID entityId,long version,String action,UUID relatedId,String relatedType,Instant occurredAt) { }
 public record Trace(UUID requestId,long total,int page,int pageSize,List<TraceEvent> events) { }
 public record Claim(@NotNull UUID taskId,@NotNull @Min(0) Long expectedVersion,@NotNull UUID confirmedCassetteId) { }
 public record Batch(@NotNull UUID batchId,@NotEmpty @Size(max=20) List<@NotNull @Valid Claim> items,@NotBlank @Size(max=2000) String reason) { }
 public record ItemResult(UUID taskId,String outcome,int status,String code,Long version,boolean replayed) { }
 public record BatchResult(UUID batchId,List<ItemResult> items) { }
}
