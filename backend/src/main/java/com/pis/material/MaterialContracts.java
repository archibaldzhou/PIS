package com.pis.material;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class MaterialContracts {
    private MaterialContracts() { }
    public record BlockCreate(@NotNull @Min(0) Long requestVersion,@NotNull UUID taskId,@NotNull @Min(0) Long taskVersion,@NotNull UUID confirmedCassetteId,@NotBlank @Size(max=2000) String reason) { }
    public record SlideCreate(@NotNull @Min(0) Long blockVersion,@NotNull UUID taskId,@NotNull @Min(0) Long taskVersion,@NotNull UUID confirmedBlockId,@NotBlank @Size(max=2000) String reason) { }
    public record DirectCreate(@NotNull @Min(0) Long requestVersion,@NotNull UUID confirmedContainerId,@NotBlank @Size(max=2000) String reason) { }
    public record Repeat(@NotNull @Min(0) Long sourceSlideVersion,@NotNull UUID taskId,@NotNull @Min(0) Long taskVersion,@NotNull UUID confirmedSourceSlideId,@NotBlank @Size(max=2000) String reason) { }
    public record VoidMaterial(@NotNull @Min(0) Long expectedVersion,@NotNull UUID confirmedMaterialId,@NotBlank @Size(max=2000) String reason) { }
    public record Entity(UUID id,UUID hospitalId,UUID requestId,UUID caseId,UUID patientId,String kind,String route,String operation,String number,String barcode,
        UUID recordId,UUID cassetteId,UUID containerId,UUID blockId,UUID sourceSlideId,UUID technicalTaskId,String state,long version,UUID createdBy,Instant createdAt,UUID cytologyPreparationId) { }
    public record Event(UUID id,long version,String action,UUID relatedId,String reason,UUID actorId,Instant occurredAt) { }
    public record View(com.pis.accession.RequestContracts.Detail request,String caseNumber,List<Entity> entities,List<com.pis.processing.TechnicalService.MaterialTask> tasks) { }
    public record Detail(Entity entity,List<Event> events) { }
}
