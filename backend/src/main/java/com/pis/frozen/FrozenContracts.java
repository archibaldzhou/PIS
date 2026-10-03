package com.pis.frozen;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.*;
public final class FrozenContracts {
 private FrozenContracts() { }
 public enum Action { RECEIVE, PREPARE, CORRECT_TIME, QC_PASS, QC_FAIL, IDENTITY_MISMATCH, DRAFT, REVIEW, TRANSFER, CLAIM, COMMUNICATE, READBACK, CONFIRM, LINK_ROUTINE }
 public record Command(@NotNull UUID confirmedCaseId,@NotNull @Min(-1) Long expectedVersion,
  @NotNull OffsetDateTime occurredAt,@NotBlank @Size(max=80) String zoneId,@NotBlank @Size(max=2000) String reason,
  UUID containerId,@Size(max=255) String site,UUID resultId,UUID relatedId,UUID targetUserId,
  @NotNull @Size(max=8000) String content,UUID routineSignatureId,@Pattern(regexp="CONSISTENT|DISCREPANCY") String comparison,@Pattern(regexp="[0-9a-f]{64}") String reviewToken) { }
 public record Candidate(UUID id,String name,boolean record,boolean review,boolean qc) { }
 public record CaseItem(UUID id,String number) { }
 public record Source(UUID id,String site) { }
 public record Event(UUID id,long version,Action action,UUID actorId,UUID targetUserId,UUID resultId,UUID relatedId,Instant occurredAt,Instant recordedAt,String zoneId,int offsetSeconds,String reason,String content,String dependency,UUID routineSignatureId,UUID routineRevisionId,String routineTemplateCode,Integer routineTemplateVersion,String comparison,String communicationMethod) { }
 public record Head(UUID id,UUID materialId,UUID containerId,String site,long version,UUID ownerId,boolean ownerActive,UUID receivedId,UUID preparedId,UUID revisionId,UUID reviewId,UUID qcId,String qcState,String stage) { }
 public record Detail(UUID caseId,String number,UUID actorId,Head head,boolean gateReady,boolean reviewValid,Instant receivedAt,Instant preparedAt,Long elapsedSeconds,List<Source> sources,List<Candidate> candidates,int page,List<Event> events,String reviewToken) { }
}
