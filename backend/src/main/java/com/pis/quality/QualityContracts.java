package com.pis.quality;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class QualityContracts {
 private QualityContracts() { }
 public enum Outcome { PASS, FAIL, PENDING, IDENTITY_MISMATCH }
 public record Assess(@NotNull @Min(-1) Long expectedVersion,@NotNull @Min(0) Long materialVersion,@Min(0) Long taskVersion,@NotNull UUID confirmedMaterialId,@NotBlank @Size(max=80) String standardVersion,@NotNull Outcome outcome,@NotBlank @Size(max=2000) String reason) { }
 public record Decision(@NotNull @Min(0) Long expectedVersion,@NotNull UUID confirmedMaterialId,@NotBlank @Size(max=2000) String reason) { }
 public record Head(UUID materialId,String state,long version,UUID assessmentId,long materialVersion,Long taskVersion,UUID repairTaskId) { }
 public record Assessment(UUID id,long materialVersion,UUID taskId,Long taskVersion,String standardVersion,String outcome,String reason,UUID actorId,Instant occurredAt) { }
 public record Event(UUID id,long version,String action,UUID assessmentId,UUID relatedTaskId,String reason,UUID actorId,Instant occurredAt) { }
 public record Item(QualitySubjects.Subject subject,Head head,String effectiveState) { }
 public record View(UUID requestId,List<Item> items) { }
 public record Detail(Item item,List<Assessment> assessments,List<Event> events,boolean exceptionReleaseEnabled) { }
}
