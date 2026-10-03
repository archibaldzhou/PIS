package com.pis.report;
import jakarta.validation.constraints.*;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
public final class ReviewContracts {
 private ReviewContracts() { }
 public enum Action { APPROVE, RETURN, SIMULATE_SIGN }
 public record Decision(@NotNull @Min(-1) Long expectedVersion,@NotNull UUID confirmedCaseId,@NotNull UUID revisionId,@NotNull @Min(0) Long draftVersion,@NotNull @Min(0) Long assignmentVersion,@NotBlank @Size(max=64) String templateCode,@NotNull @Min(1) Integer templateVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String dependencyToken,@NotBlank @Size(max=2000) String reason,@NotNull Boolean simulationAcknowledged) { }
 public record Policy(String code,boolean separateAuthorReview,boolean separateReviewSign) { }
 public record Event(UUID id,long version,UUID revisionId,long draftVersion,Action action,UUID actorId,UUID reviewId,String reason,Instant occurredAt) { }
 public record History(UUID caseId,int page,List<Event> events) { }
 public record Detail(UUID caseId,UUID patientId,String number,long assignmentVersion,long version,String state,boolean ready,boolean canReview,boolean canSimulateSign,Policy policy,String dependencyToken,ReportContracts.Revision draft,List<Event> events) { }
}
