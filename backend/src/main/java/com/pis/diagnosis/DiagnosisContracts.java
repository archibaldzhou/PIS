package com.pis.diagnosis;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class DiagnosisContracts {
 private DiagnosisContracts() { }
 public enum State { ALL,UNASSIGNED,ASSIGNED,ACTIVE }
 public enum Action { ASSIGN,CLAIM,TRANSFER }
 public record Decision(@NotNull @Min(-1) Long expectedVersion,@NotNull UUID confirmedCaseId,UUID targetUserId,@NotBlank @Size(max=2000) String reason) { }
 public record Item(UUID caseId,UUID requestId,UUID patientId,String number,String state,long version,UUID ownerId,boolean ready) { }
 public record Candidate(UUID id,String name) { }
 public record Event(UUID id,long version,String action,UUID actorId,UUID previousOwnerId,UUID nextOwnerId,String reason,Instant occurredAt) { }
 public record Detail(Item item,UUID actorId,boolean canAssign,boolean canDiagnose,List<Candidate> candidates,List<Event> events) { }
 public record Page(long total,int page,int pageSize,List<Item> items) { }
}
