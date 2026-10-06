package com.pis.accession;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RequestContracts {
    private RequestContracts() { }
    public enum Laterality { NONE, LEFT, RIGHT, BILATERAL, UNKNOWN }
    public record ContainerInput(@NotBlank @Size(max=255) String site, @NotNull Laterality laterality,
        @Min(1) @Max(999) int materialQuantity, @NotNull @Size(max=128) String fixative, Instant fixedAt) { }
    public record Draft(@NotNull @Size(max=4000) String clinicalHistory, Instant sampledAt,
        @NotNull @Size(min=1,max=20) List<@NotNull @Valid ContainerInput> containers) { }
    public record Create(@NotNull UUID scopeId, @NotNull UUID encounterId, @NotNull @Valid Draft draft) { }
    public record ManualCreate(@NotNull UUID scopeId,
        @NotBlank @Size(max=255) @Pattern(regexp="\\S(?:[\\s\\S]*\\S)?") String patientName,
        @NotBlank @Size(max=255) @Pattern(regexp="\\S(?:[\\s\\S]*\\S)?") String encounterNumber,
        @NotNull @Valid Draft draft) { }
    public record Edit(@NotNull @Min(0) Long expectedVersion, @NotNull @Valid Draft draft) { }
    public record Submit(@NotNull @Min(0) Long expectedVersion) { }
    public record Encounter(UUID id, UUID patientId, String patientLabel, String encounterNumber) { }
    public record Container(UUID id, String site, String laterality, int materialQuantity, String fixative, Instant fixedAt) { }
    public record Detail(UUID id, UUID scopeId, long version, String requestNumber, UUID patientId, String patientLabel,
        UUID encounterId, String encounterNumber, String department, Instant requestedAt, String state,
        String clinicalHistory, Instant sampledAt, List<Container> containers) { }
    public record Page(List<Detail> items, long total, int page, int pageSize) { }
}
