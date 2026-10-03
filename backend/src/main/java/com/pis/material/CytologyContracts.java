package com.pis.material;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.Instant;
public final class CytologyContracts {
 private CytologyContracts() { }
 public enum Action { REGISTER,QC_PASS,QC_FAIL,IDENTITY_MISMATCH,PREPARE,COMPLETE,FAIL }
 public enum Path { DIRECT_SMEAR,LIQUID_BASED,CELL_BLOCK }
 public record Command(@NotNull UUID confirmedContainerId,@NotNull @Min(-1) Long expectedVersion,@NotBlank @Size(max=2000) String reason,
  @NotNull @Size(max=1000) String metadata,Path path,@Min(0) @Max(99) int transferred,UUID repeatOf,UUID preparationId,@Min(-1) long preparationVersion,
  @Min(0) @Max(99) int consumed,@Min(0) @Max(99) int discarded,@Min(0) @Max(99) int returned,@Min(0) @Max(20) int slides) { }
 public record Specimen(UUID id,UUID containerId,UUID patientId,UUID caseId,int initialQuantity,int remaining,long version,String qcState,long qcVersion,String sampleDescription) { }
 public record Preparation(UUID id,Path path,String metadata,int transferred,long sourceQcVersion,UUID repeatOf,String state,long version) { }
 public record Event(UUID id,long version,UUID preparationId,String action,int transferred,int consumed,int discarded,int returned,int slides,String reason,UUID actorId,Instant recordedAt) { }
 public record Detail(UUID requestId,UUID containerId,UUID patientId,UUID caseId,String caseNumber,Specimen specimen,List<Preparation> preparations,List<MaterialContracts.Entity> materials,int page,List<Event> events) { }
}
