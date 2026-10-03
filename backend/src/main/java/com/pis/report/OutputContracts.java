package com.pis.report;
import jakarta.validation.constraints.*;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
public final class OutputContracts {
 private OutputContracts() { }
 public enum Kind { PREVIEW, DOWNLOAD, PRINT_REQUEST, REPRINT_REQUEST, USER_REPORTED_PRINTED, USER_REPORTED_FAILED, USER_REPORTED_CANCELLED }
 public record Create(@NotNull UUID confirmedCaseId,@NotNull UUID signatureId,@NotNull @Min(0) Long signatureVersion,@NotNull UUID revisionId,@NotBlank @Size(max=2000) String reason) { }
 public record Operation(@NotNull UUID confirmedCaseId,@NotNull @Min(0) Long artifactVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256,@NotNull @Min(-1) Long expectedVersion,UUID requestId,@NotBlank @Size(max=2000) String reason) { }
 public record Artifact(UUID id,UUID caseId,long version,UUID signatureId,long signatureVersion,UUID revisionId,long draftVersion,String templateCode,int templateVersion,String schemaCode,String dependencyToken,String rendererVersion,String fontHash,String sha256,int byteSize,int pages,Instant createdAt) { }
 public record Detail(UUID caseId,UUID signatureId,long signatureVersion,UUID revisionId,boolean dependenciesCurrent,Artifact artifact,long activityVersion) { }
 public record Event(UUID id,long version,Kind kind,UUID requestId,UUID actorId,String reason,Instant occurredAt) { }
 public record History(UUID caseId,UUID artifactId,int page,List<Event> events) { }
}
