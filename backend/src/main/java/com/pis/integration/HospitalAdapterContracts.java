package com.pis.integration;
import java.util.*;import java.time.Instant;import jakarta.validation.constraints.*;
public final class HospitalAdapterContracts {
 private HospitalAdapterContracts(){}
 public enum Adapter { HIS, BILLING, DEVICE }
 public enum Action { CLAIM, RECEIVE, ACK, RECONCILE, TIMEOUT, FAIL, POISON, CANCEL, REPAIR }
 public record Submit(@NotNull UUID confirmedCaseId,@NotNull UUID patientId,@NotNull UUID sourceId,@NotNull Adapter adapter,@NotBlank @Pattern(regexp="SYN-HOSPITAL-1") String schema,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String externalId,@NotNull @Min(1) @Max(1000) Integer sequence,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String code,UUID materialId,@Min(0) @Max(1000000) Long amountMinor,@NotBlank @Size(max=500) String note){}
 public record Command(@NotNull UUID confirmedCaseId,@NotNull UUID sourceId,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull @Min(0) @Max(98) Long expectedVersion,UUID attemptId,@NotBlank @Size(max=500) String reason,@AssertTrue boolean confirmed){}
 public record Item(UUID id,UUID requestId,UUID caseId,UUID sourceId,Adapter adapter,String externalId,int sequence,String payloadHash,long version,String state,int attempts,UUID attemptId,Instant leaseUntil,Instant nextAt,String errorCode,boolean locallyRecorded){}
 public record View(UUID requestId,UUID caseId,UUID patientId,UUID sourceId,int page,List<Item> items,Map<String,String> capabilities){}
 public record Event(long version,String action,String state,String errorCode,UUID actorId,String reason,Instant createdAt){}
}
