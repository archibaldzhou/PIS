package com.pis.label;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class LabelContracts {
    private LabelContracts() { }
    public record Create(@NotNull @Min(0) Long requestVersion,@NotNull @Min(0) Long containerVersion) { }
    public record Change(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=2000) String reason) { }
    public record Verify(@NotNull UUID containerId,@NotBlank @Size(max=64) String barcode) { }
    public record Job(UUID id,UUID containerId,String barcode,UUID parentJobId,String templateVersion,String state,long version,int attempts,
        long requestVersion,long containerVersion,UUID patientId,String patientLabel,String encounterNumber,String requestNumber,String caseNumber,
        String site,String laterality,String reason,UUID createdBy,Instant createdAt) { }
    public record Event(UUID id,long jobVersion,String action,String reason,UUID actorId,Instant occurredAt) { }
    public record View(Job job,List<Event> events) { }
    public record Checked(boolean matches) { }
}
