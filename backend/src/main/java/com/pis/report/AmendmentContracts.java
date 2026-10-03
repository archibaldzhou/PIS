package com.pis.report;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.Instant;
public final class AmendmentContracts {
 private AmendmentContracts() { }
 public enum Kind { ADDENDUM, CORRECTION }
 public record Create(@NotNull UUID confirmedCaseId,@NotNull @Min(0) Long expectedVersion,@NotNull @Min(0) Long assignmentVersion,@NotNull UUID baseSignatureId,@NotNull UUID baseRevisionId,@NotNull @Min(0) Long baseDraftVersion,@NotNull Kind kind,@NotBlank @Size(max=2000) String reason) { }
 public record Node(UUID id,long version,Kind kind,UUID parentId,UUID baseSignatureId,UUID baseRevisionId,long baseDraftVersion,UUID startRevisionId,long startVersion,UUID actorId,String reason,Instant createdAt,UUID newSignatureId,String downstreamState) { }
 public record Detail(UUID caseId,long version,long assignmentVersion,boolean ready,UUID draftId,UUID revisionId,long draftVersion,UUID frozenSignatureId,UUID frozenRevisionId,boolean pending,boolean canCreate,int page,List<Node> nodes) { }
 public record Snapshot(UUID caseId,UUID signatureId,boolean currentFrozen,ReportContracts.Revision revision,UUID artifactId) { }
}
