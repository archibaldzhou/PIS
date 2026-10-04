package com.pis.ai;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class AiTaskContracts {
 private AiTaskContracts(){}
 public record Submit(@NotNull UUID assessmentId,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}")String workerSchema,@NotBlank @Size(max=500)String reason){}
 public record Command(@NotNull @Min(0)Long expectedVersion,@Min(0) @Max(3)Integer generation,UUID leaseId,@NotBlank @Size(max=500)String reason){}
 public record Callback(@NotNull @Min(0)Long expectedVersion,@NotNull @Min(1) @Max(3)Integer generation,@NotNull UUID leaseId,@NotNull UUID callbackId,@NotBlank @Size(max=64)String source,@NotBlank @Size(max=64)String schema,@NotNull UUID artifactId,@NotBlank @Pattern(regexp="[a-f0-9]{64}")String artifactHash){}
 public record Job(UUID id,UUID requestId,UUID scanId,UUID assessmentId,UUID scopeId,UUID hospitalId,UUID ownerId,long ownerAuthVersion,AiRegistryService.WorkerBinding binding,String bindingHash,String workerSchema,String state,long version,int generation,Integer progress,UUID leaseId,UUID leaseActor,Instant leaseUntil,Instant deadline,Instant retryAt,String errorCode,UUID artifactId){}
 public record View(Job job,String effectiveState,String invalidReason,boolean clinicalExecutionAllowed,List<Map<String,Object>> history){}
 public record Listing(UUID requestId,UUID scanId,int page,List<Job> jobs,String capability){}
}
