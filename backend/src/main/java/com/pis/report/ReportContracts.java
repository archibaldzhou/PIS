package com.pis.report;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
public final class ReportContracts {
 private ReportContracts() { }
 public record Save(@NotNull @Min(-1) Long expectedVersion,@NotNull @Min(0) Long assignmentVersion,@NotNull UUID confirmedCaseId,@NotBlank @Size(max=64) String templateCode,@NotNull @Min(1) Integer templateVersion,@NotNull JsonNode fields,@NotBlank @Size(max=2000) String reason) { }
 public record Template(String code,int version,String title,String schemaCode) { }
 public record Revision(UUID id,UUID caseId,long version,String templateCode,int templateVersion,JsonNode fields,long assignmentVersion,UUID authorId,String reason,Instant createdAt) { }
 public record Detail(com.pis.diagnosis.DiagnosisService.ReportContext context,Revision current,List<Template> templates) { }
 public record History(UUID caseId,int page,List<Revision> revisions) { }
}
