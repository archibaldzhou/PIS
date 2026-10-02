package com.pis.label;
import java.util.Optional;
import java.util.UUID;
/** Public read-only adapter boundary. Providers recheck READ scope and never send device commands. */
public interface LabelSubjectProvider {
    Optional<Subject> find(UUID targetId);
    record Subject(UUID targetId,UUID containerId,UUID materialId,UUID hospitalId,UUID requestId,long requestVersion,long targetVersion,
        UUID scopeId,UUID patientId,String patientLabel,String encounterNumber,String requestNumber,String caseNumber,String site,String laterality,String requestState,boolean active) { }
}
