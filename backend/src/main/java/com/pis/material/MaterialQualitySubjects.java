package com.pis.material;
import com.pis.accession.RequestService;
import com.pis.processing.TechnicalService;
import com.pis.processing.TechnicalContracts;
import com.pis.quality.QualitySubjects;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class MaterialQualitySubjects implements QualitySubjects {
 private final MaterialQueries queries; private final RequestService requests; private final JdbcTemplate jdbc; private final TechnicalService technical;
 public MaterialQualitySubjects(MaterialQueries queries,RequestService requests,JdbcTemplate jdbc,TechnicalService technical) { this.queries=queries; this.requests=requests; this.jdbc=jdbc; this.technical=technical; }
 public Subject subject(UUID id) {
  var m=queries.row(id); return convert(m,requests.detail(m.requestId()).scopeId(),technical.qualityTasks(m.requestId()),blocked(m.requestId()));
 }
 private Subject convert(MaterialContracts.Entity m,UUID scope,List<TechnicalService.QualityTask> tasks,java.util.Set<UUID> blocked) {
  var task=tasks.stream().filter(t->t.id().equals(m.technicalTaskId())).findFirst().orElse(null);
  return new Subject(m.id(),m.hospitalId(),m.requestId(),m.caseId(),m.patientId(),scope,m.number(),m.kind(),m.route(),blocked.contains(m.id())?"SOURCE_QUARANTINED":m.state(),m.version(),m.cassetteId(),m.blockId(),m.technicalTaskId(),task==null?null:task.version(),task==null?null:task.state());
 }
 public List<Subject> subjects(UUID rid) {
  var scope=requests.detail(rid).scopeId(); var tasks=technical.qualityTasks(rid);var blocked=blocked(rid);
  return jdbc.query("SELECT * FROM material_entity WHERE request_id=? ORDER BY created_at,id LIMIT 100",MaterialQueries.MAPPER,rid).stream().map(m->convert(m,scope,tasks,blocked)).toList();
 }
 private java.util.Set<UUID> blocked(UUID rid){return new java.util.HashSet<>(jdbc.queryForList("SELECT m.id FROM material_entity m WHERE m.request_id=? AND (EXISTS(SELECT 1 FROM cytology_material_gate g WHERE g.id=m.id AND g.state<>'PASS') OR EXISTS(SELECT 1 FROM cytology_specimen s WHERE s.request_id=m.request_id AND s.qc_state='IDENTITY_MISMATCH'))",UUID.class,rid));}
 public UUID rework(Subject source,String reason) { return technical.qualityRework(source.id(),source.taskId(),new TechnicalContracts.Decision(source.taskVersion(),source.cassetteId(),reason)); }
}
