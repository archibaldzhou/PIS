package com.pis.storage;
import java.time.Instant;import java.util.*;import jakarta.validation.constraints.*;
public final class StorageContracts {
 private StorageContracts(){}
 public record Reserve(UUID assetId,@NotNull @Min(-1) Long expectedHead,@NotNull UUID confirmedCaseId,@NotNull @Min(25) @Max(67108864) Long byteSize,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256,@NotBlank @Pattern(regexp="SYNTHETIC_ORIGINAL|SYNTHETIC_WORKER_ARTIFACT") String purpose,@NotBlank @Pattern(regexp="application/octet-stream") String mediaType){}
 public record Command(@NotNull UUID confirmedCaseId,@NotNull @Min(0) Long expectedVersion){}
 public record Version(UUID id,UUID assetId,UUID caseId,UUID rootId,long ordinal,long version,String state,long byteSize,String sha256,String purpose,Instant createdAt){}
 public record View(UUID requestId,UUID caseId,String provider,UUID providerRootId,String s3,int page,List<Version> versions){}
 public record Capacity(String provider,Long volumeTotal,Long volumeUsable,long reservedBytes,long syntheticQuota,Instant measuredAt){}
 public record Cleanup(UUID requestId,List<UUID> failedStagingCandidates,boolean dryRun,String action){}
}
