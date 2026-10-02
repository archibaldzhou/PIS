package com.pis.quality;
import java.util.List;
import java.util.UUID;
/** Provider validates READ and source ownership. No QC decision is delegated to clients. */
public interface QualitySubjects {
    record Subject(UUID id,UUID hospitalId,UUID requestId,UUID caseId,UUID patientId,UUID scopeId,String number,String kind,String route,String state,long version,UUID cassetteId,UUID blockId,UUID taskId,Long taskVersion,String taskState) { }
    Subject subject(UUID id);
    List<Subject> subjects(UUID requestId);
    UUID rework(Subject source,String reason);
}
