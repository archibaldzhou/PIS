package com.pis.scan;
import java.time.Instant;import java.util.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
public final class ScanContracts {
 private ScanContracts(){}
 public record Create(@NotNull UUID confirmedCaseId,@NotNull UUID patientId,@NotNull UUID slideId,@NotNull UUID objectId,@NotNull @Min(-1) Long expectedHead,UUID previousId,@NotBlank @Size(max=64) String barcode,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String sourceCode,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String scannerCode,@NotBlank @Size(max=500) String reason){}
 public record Batch(@NotEmpty @Size(max=10) List<@NotNull @Valid Create> items){}
 public record Command(@NotNull @Min(0) Long expectedVersion,UUID leaseId,@NotBlank @Size(max=500) String reason){}
 public record Item(UUID objectId,int status,UUID scanId,String code){}
 public record Job(UUID id,UUID requestId,UUID caseId,UUID patientId,UUID claimedCaseId,UUID claimedPatientId,UUID slideId,UUID objectId,String objectHash,long objectSize,long ordinal,UUID previousId,String sourceBasis,String barcode,String sourceCode,String scannerCode,String reason,String state,String errorCode,long version,int attempts,UUID leaseId,UUID leaseActor,Instant leaseUntil,Instant retryAt,Integer width,Integer height,String invalidReason){}
 public record Event(UUID scanId,long version,String action,String state,String errorCode,UUID leaseId,int attempts,String reason,Instant occurredAt){}
 public record Source(UUID id,String barcode,long version,String status){}
 public record View(UUID requestId,UUID caseId,UUID patientId,int page,List<Job> jobs,List<Source> slides,Map<String,String> capabilities,String publication){}
}
