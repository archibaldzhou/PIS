package com.pis.material;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
public final class StainContracts {
 private StainContracts() { }
 public enum Action { CREATE,ADD,FREEZE,CONTROL_PASS,CONTROL_FAIL,REVOKE,RESULT }
 public enum Kind { SPECIAL,IHC }
 public enum TechnicalQc { TECH_PASS,TECH_FAIL }
 public record Source(@NotNull UUID id,@NotNull @Min(0) Long version) { }
 public record Command(@NotNull UUID confirmedRequestId,@NotNull @Min(-1) Long expectedVersion,@NotBlank @Size(max=2000) String reason,
  @NotNull @Size(max=20) List<@NotNull @Valid Source> sources,Kind kind,@Size(max=44) String projectCode,@Min(1) @Max(9999) Integer projectVersion,
  @Size(max=44) String schemeCode,@Min(1) @Max(9999) Integer schemeVersion,@Size(max=1000) String metadata,@Size(max=120) String reagentLot,LocalDate expiresOn,
  @Size(max=255) String controlReference,UUID rerunOf,UUID orderId,Long frozenVersion,UUID controlEventId,TechnicalQc technicalQc,@NotNull @Size(max=4000) String content) { }
 public record Batch(UUID id,String state,long version,Long frozenVersion,UUID controlId,UUID controlEventId,String controlReference,String reagentLot,LocalDate expiresOn,UUID rerunOf,String kind,String projectCode,int projectVersion,String schemeCode,int schemeVersion,String metadata) { }
 public record SourceView(UUID id,String number,long version,UUID blockId,String route,String state,String qcState) { }
 public record Order(UUID id,UUID sourceId,long sourceVersion,UUID outputId,UUID resultId,String effectiveState,String invalidReason) { }
 public record Event(UUID id,long version,String action,UUID orderId,Long frozenVersion,UUID controlEventId,String technicalQc,String content,String reason,UUID actorId,Instant recordedAt) { }
 public record Detail(UUID requestId,UUID patientId,UUID caseId,String caseNumber,List<Batch> batches,Batch selected,List<SourceView> sources,List<Order> orders,int page,List<Event> events) { }
}
