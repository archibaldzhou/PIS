package com.pis.report;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.Instant;
public final class DeliveryContracts {
 private DeliveryContracts() { }
 public enum Action { CLAIM, RECEIVE, ACK, RECONCILE, FAIL, TIMEOUT, POISON }
 public record Command(@NotNull UUID confirmedCaseId,@NotNull UUID artifactId,@NotNull UUID signatureId,@NotNull UUID revisionId,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256,@NotBlank @Pattern(regexp="LOCAL_SIM") String destination,@NotNull @Min(0) Long expectedVersion,UUID attemptId,@NotBlank @Size(max=2000) String reason) { }
 public record Item(UUID id,UUID artifactId,UUID signatureId,UUID revisionId,String sha256,String destination,UUID predecessor,long version,String state,int attempts,UUID attemptId,Instant leaseUntil,Instant nextAt) { }
 public record Event(long version,String action,String state,UUID attemptId,UUID actorId,String reason,Instant occurredAt) { }
 public record Detail(UUID caseId,String caStatus,List<Item> items) { }
}
