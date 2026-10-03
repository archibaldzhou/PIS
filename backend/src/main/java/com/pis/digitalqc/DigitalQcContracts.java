package com.pis.digitalqc;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class DigitalQcContracts {
    private DigitalQcContracts() {}
    public record Region(@Min(0) int x, @Min(0) int y, @Min(1) int width, @Min(1) int height,
                         @NotBlank @Size(max=200) String note) {}
    public record Evaluation(@NotNull @Min(-1) Long expectedVersion, @NotNull @Min(0) Long scanVersion,
        @NotNull UUID objectId, @NotNull UUID slideId, @NotBlank @Pattern(regexp="[a-f0-9]{64}") String objectHash,
        @NotBlank @Pattern(regexp="SYN-DIGITAL-QC-1") String checklist,
        @NotBlank @Pattern(regexp="PASS|FAIL|UNKNOWN") String coverage,
        @NotBlank @Pattern(regexp="PASS|FAIL|UNKNOWN") String focus,
        @NotBlank @Pattern(regexp="PASS|FAIL|UNKNOWN") String missing,
        @NotNull @Min(0) @Max(100) Integer coveragePercent,
        @NotNull @Min(0) @Max(1000000) Integer missingTiles,
        @NotNull @Size(max=20) List<@NotNull @Valid Region> regions,
        @NotBlank @Size(max=1000) String note) {}
    public record Command(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(0) Long assessmentVersion,
                          @NotBlank @Size(max=1000) String reason) {}
    public record Assessment(long version,long scanVersion,UUID objectId,String objectHash,UUID slideId,
        String sourceBasis,String checklist,String coverage,String focus,String missing,int coveragePercent,
        int missingTiles,List<Region> regions,String note,boolean passed,UUID actorId,Instant occurredAt) {}
    public record Event(long version,String action,long assessmentVersion,UUID actorId,String reason,Instant occurredAt) {}
    public record View(UUID requestId,UUID scanId,UUID caseId,UUID slideId,UUID objectId,String objectHash,
        long scanVersion,long ordinal,boolean currentScan,long version,String state,String effectiveState,
        String invalidReason,Assessment assessment,List<Event> events,String capability) {}
}
