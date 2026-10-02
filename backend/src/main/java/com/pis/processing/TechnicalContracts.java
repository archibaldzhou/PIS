package com.pis.processing;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class TechnicalContracts {
    private TechnicalContracts() { }
    public enum Kind { PROCESSING, EMBEDDING, SECTIONING }
    public record Create(@NotNull @Min(0) Long requestVersion,@NotNull UUID cassetteId,@NotNull Kind kind,UUID predecessorId,@NotBlank @Size(max=2000) String reason) { }
    public record Decision(@NotNull @Min(0) Long expectedVersion,@NotNull UUID confirmedCassetteId,@NotBlank @Size(max=2000) String reason) { }
    public record Task(UUID id,UUID cassetteId,String kind,String state,UUID ownerId,UUID predecessorId,UUID reworkOf,long version,UUID createdBy,Instant createdAt) { }
    public record Event(UUID id,long version,String action,UUID actorId,UUID previousOwnerId,UUID nextOwnerId,UUID relatedTaskId,String reason,Instant occurredAt) { }
    public record View(com.pis.grossing.GrossService.Released source,UUID actorId,List<Task> tasks) { }
    public record Detail(Task task,List<Event> events) { }
}
