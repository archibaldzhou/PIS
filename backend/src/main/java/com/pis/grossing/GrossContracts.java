package com.pis.grossing;
import com.pis.accession.RequestContracts.Detail;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class GrossContracts {
    private GrossContracts() { }
    public record Create(@NotNull @Min(0) Long requestVersion,@NotNull @Size(max=8000) String description) { }
    public record Description(@NotNull @Min(0) Long expectedVersion,@NotNull @Size(max=8000) String description,@NotBlank @Size(max=2000) String reason) { }
    public record Decision(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=2000) String reason) { }
    public record AddCassette(@NotNull @Min(0) Long expectedVersion,@NotNull @Size(min=1,max=20) List<@NotNull UUID> containerIds,@NotBlank @Size(max=255) String site,@Min(1) @Max(99) int pieces) { }
    public record AddPhoto(@NotNull @Min(0) Long expectedVersion,@NotNull UUID containerId,@NotBlank @Size(max=22000) String base64,@NotNull @Size(max=255) String caption) { }
    public record Cassette(UUID id,String number,String site,int pieces,String state,long version,List<UUID> containerIds) { }
    public record Photo(UUID id,UUID containerId,String caption,String sha256,int bytes,int width,int height,Instant withdrawnAt) { }
    public record Revision(long version,String description,String reason,UUID actorId,Instant createdAt) { }
    public record Event(UUID id,long version,String action,UUID targetId,String reason,UUID actorId,Instant occurredAt) { }
    public record Record(UUID id,String state,long version,String description,List<Cassette> cassettes,List<Photo> photos,List<Revision> revisions,List<Event> events) { }
    public record View(Detail request,UUID caseId,String caseNumber,Record record) { }
}
