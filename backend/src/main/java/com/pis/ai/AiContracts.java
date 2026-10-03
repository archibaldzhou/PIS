package com.pis.ai;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public final class AiContracts {
 private AiContracts(){}
 public record Metadata(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String digest,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String preprocessing,@NotBlank @Size(max=64) String configSchema,@Min(1) @Max(16) int maxTiles,@NotBlank @Size(max=64) String inputContract,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String pathology,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String stain,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String scannerCode,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String scannerVersion,@NotBlank @Size(max=64) String quality,@NotBlank @Size(max=64) String calibration,@NotBlank @Size(max=64) String approvalScope,@NotBlank @Size(max=64) String evidence,@NotNull @Size(max=200) String evidenceRef,@NotBlank @Size(max=500) String limitations){}
 public record Register(@NotNull UUID modelId,@NotNull @Min(-1) @Max(98) Long expectedHead,@NotBlank @Pattern(regexp="SYN-[A-Za-z0-9_-]{1,60}") String code,@NotNull @Valid Metadata metadata,@NotBlank @Size(max=500) String reason){}
 public record StateChange(@NotNull @Min(0) @Max(98) Long expectedVersion,@NotBlank @Size(max=64) String state,@NotBlank @Size(max=500) String reason){}
 public record Profile(@NotBlank @Pattern(regexp="UNKNOWN|SYN-[A-Za-z0-9_-]{1,60}") String pathology,@NotBlank @Pattern(regexp="UNKNOWN|SYN-[A-Za-z0-9_-]{1,60}") String stain,@NotBlank @Pattern(regexp="UNKNOWN|SYN-[A-Za-z0-9_-]{1,60}") String scannerVersion){}
 public record ProfileInput(@NotNull @Min(0) Long publicationVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String manifestHash,@NotNull @Min(-1) @Max(98) Long expectedVersion,@NotNull @Valid Profile profile,@NotBlank @Size(max=500) String reason){}
 public record Assess(@NotNull UUID modelVersionId,@NotNull @Min(0) Long stateVersion,@NotNull @Min(0) Long profileVersion,@NotNull @Min(0) Long publicationVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String manifestHash,@Min(0) Long calibrationVersion,@NotBlank @Size(max=500) String reason){}
 public record Model(UUID id,UUID modelId,UUID scopeId,String code,long ordinal,long head,long stateVersion,String state,Metadata metadata){}
 public record ProfileView(long version,long publicationVersion,String manifestHash,Profile profile){}
 public record Permissions(boolean register,boolean validate,boolean assess,boolean clinicalApproval,boolean execution){}
 public record Catalog(UUID scopeId,int page,List<Model> models,Permissions permissions,String capability){}
 public record ScanView(UUID requestId,UUID scanId,UUID scopeId,long publicationVersion,String manifestHash,String scannerCode,ProfileView profile,Long calibrationVersion,String calibration){}
 public record Decision(UUID requestId,UUID scanId,UUID scopeId,UUID objectId,String objectHash,long scanVersion,String manifestHash,long publicationVersion,UUID modelVersionId,String modelDigest,long modelOrdinal,long modelHead,long stateVersion,long profileVersion,Long calibrationVersion,String outcome,List<String> reasons,boolean executionAllowed,String clinicalEvidence){}
}
