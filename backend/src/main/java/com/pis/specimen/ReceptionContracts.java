package com.pis.specimen;

import com.pis.accession.RequestContracts.Detail;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReceptionContracts {
    private ReceptionContracts() { }
    public enum Category { IDENTITY, QUANTITY, INFORMATION }
    public record Check(@NotNull @Min(0) Long expectedVersion,@NotNull UUID patientId,
        @NotBlank @Size(max=255) String encounterNumber,@NotNull @Size(max=20) List<@NotNull UUID> containerIds) { }
    public record ExceptionInput(@NotNull @Min(0) Long expectedVersion,@NotNull Category category,
        @NotBlank @Size(max=2000) String reason) { }
    public record Decision(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=2000) String reason) { }
    public record Event(UUID id,long requestVersion,String action,String category,String reason,UUID actorId,Instant occurredAt) { }
    public record View(Detail request,String caseNumber,List<Event> events) { }
}
