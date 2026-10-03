package com.pis.report;
import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public final class ConsultationContracts {
 private ConsultationContracts() { }
 public enum Action { CREATE,ACCEPT,REJECT,WITHDRAW,REVOKE,RETURN,OPINION,SUMMARY,CONFIRM,ADOPT }
 public record Command(@NotNull UUID confirmedCaseId,@NotNull @Min(-1) Long expectedVersion,@NotBlank @Size(max=2000) String reason,
  UUID revisionId,@Min(0) Long assignmentVersion,@Size(max=20) String kind,@Size(max=2000) String purpose,Instant expiresAt,
  @NotNull @Size(max=10) List<@NotNull UUID> invitees,UUID targetId,@Size(max=20) String disposition,@NotNull @Size(max=4000) String content,
  @NotNull @Size(max=10) List<@NotNull UUID> opinionIds,UUID summaryId) { }
 public record Head(UUID id,UUID caseId,UUID revisionId,long assignmentVersion,@com.fasterxml.jackson.annotation.JsonIgnore String dependencies,String materialBasis,String kind,String purpose,Instant expiresAt,UUID createdBy,String state,long version,UUID summaryId) { }
 public record Member(UUID userId,String state,UUID opinionId,UUID confirmedSummaryId) { }
 public record Event(UUID id,long version,String action,UUID actorId,UUID targetId,String disposition,String content,String reason,UUID summaryId,UUID adoptedRevisionId,Instant recordedAt) { }
 public record View(UUID caseId,UUID patientId,String number,UUID actorId,boolean owner,List<Head> consultations,Head selected,ReportContracts.Revision basis,List<Member> members,List<Event> events,List<Event> opinions,Event summary,List<com.pis.diagnosis.DiagnosisContracts.Candidate> candidates,String invalidReason,boolean ready,int page) { }
}
