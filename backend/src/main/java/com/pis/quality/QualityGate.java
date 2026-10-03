package com.pis.quality;
import com.pis.api.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.quality.QualityContracts.*;
/** Internal public transaction boundary. Callers authorize and lock the owning request first. */
@Component
public class QualityGate {
 private final JdbcTemplate jdbc;
 public QualityGate(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 private static final org.springframework.jdbc.core.RowMapper<Head> MAPPER=(r,i)->new Head(r.getObject("material_id",UUID.class),r.getString("state"),r.getLong("version"),r.getObject("assessment_id",UUID.class),r.getLong("material_version"),r.getObject("task_version",Long.class),r.getObject("repair_task_id",UUID.class));
 public java.util.Map<UUID,Head> heads(UUID request) {
  return jdbc.query("SELECT * FROM quality_head WHERE request_id=? ORDER BY material_id LIMIT 100",MAPPER,request).stream().collect(java.util.stream.Collectors.toMap(Head::materialId,h->h));
 }
 public Head head(UUID id) {
  var rows=jdbc.query("SELECT * FROM quality_head WHERE material_id=?",MAPPER,id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public String effective(UUID id,long version,Long taskVersion) {
  return effective(head(id),version,taskVersion);
 }
 public String effective(Head h,long version,Long taskVersion) {
  if(h==null) return "NOT_ASSESSED";
  if(!h.state().equals("PASS")) return h.state();
  return h.materialVersion()==version && java.util.Objects.equals(h.taskVersion(),taskVersion)?"PASS":"INVALIDATED";
 }
 public void material(UUID id,long version,Long taskVersion) {
  if(jdbc.queryForObject("SELECT count(*) FROM stain_material_gate WHERE id=? AND state<>'PASS'",Long.class,id)>0)throw blocked();
  if(jdbc.queryForObject("SELECT count(*) FROM cytology_material_gate WHERE id=? AND state<>'PASS'",Long.class,id)>0)throw blocked();
  if(jdbc.queryForObject("SELECT count(*) FROM material_entity m JOIN cytology_specimen s ON s.request_id=m.request_id WHERE m.id=? AND s.qc_state='IDENTITY_MISMATCH'",Long.class,id)>0)throw blocked();
  String state=effective(id,version,taskVersion);
  if(!state.equals("PASS")&&!state.equals("NOT_ASSESSED")) throw blocked();
 }
 public void task(UUID id,long version) {
  if(id!=null && jdbc.queryForObject("SELECT count(*) FROM quality_head WHERE task_id=? AND (state<>'PASS' OR task_version<>?)",Long.class,id,version)>0) throw blocked();
 }
 public void cassette(UUID request,UUID cassette,UUID recoveryTask) {
  if(jdbc.queryForObject("SELECT count(*) FROM quality_head WHERE request_id=? AND cassette_id=? AND state<>'PASS' AND (?::uuid IS NULL OR repair_task_id IS DISTINCT FROM ?)",Long.class,request,cassette,recoveryTask,recoveryTask)>0) throw blocked();
 }
 public void recovery(UUID request,UUID cassette,UUID material,UUID task) {
  var h=head(material);
  if(h==null||!h.state().equals("REWORK_REQUIRED")||jdbc.queryForObject("SELECT count(*) FROM quality_head WHERE material_id=? AND task_id=?",Long.class,material,task)!=1) throw blocked();
  if(jdbc.queryForObject("SELECT count(*) FROM quality_head WHERE request_id=? AND cassette_id=? AND material_id<>? AND state<>'PASS'",Long.class,request,cassette,material)>0) throw blocked();
 }
 public void repair(UUID material,UUID selectedTask) {
  var h=head(material); if(h==null) return;
  if(h.state().equals("IDENTITY_MISMATCH")) throw blocked();
  if(!h.state().equals("PASS") && (h.repairTaskId()==null||!h.repairTaskId().equals(selectedTask))) throw blocked();
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void invalidateMaterial(UUID material,UUID actor,String reason) { invalidate("material_id=?",material,actor,reason); }
 @Transactional(propagation=Propagation.MANDATORY)
 public void reworkTask(UUID task,UUID replacement,UUID actor,String reason) {
  for(var material:jdbc.queryForList("SELECT material_id FROM quality_head WHERE task_id=? AND state IN ('PASS','FAIL','PENDING') ORDER BY material_id",UUID.class,task)) {
   var h=head(material);
   if(jdbc.update("UPDATE quality_head SET state='REWORK_REQUIRED',repair_task_id=?,version=version+1 WHERE material_id=? AND version=?",replacement,material,h.version())!=1) throw blocked();
   event(material,h.version()+1,"REWORK",h.assessmentId(),replacement,reason,actor);
  }
 }
 private void invalidate(String where,UUID id,UUID actor,String reason) {
  for(var material:jdbc.queryForList("SELECT material_id FROM quality_head WHERE "+where+" AND state IN ('PASS','FAIL','PENDING') ORDER BY material_id",UUID.class,id)) {
   var h=head(material);
   if(jdbc.update("UPDATE quality_head SET state='INVALIDATED',version=version+1 WHERE material_id=? AND version=?",material,h.version())!=1) throw blocked();
   event(material,h.version()+1,"INVALIDATE",h.assessmentId(),null,reason,actor);
  }
 }
 public void event(UUID material,long version,String action,UUID assessment,UUID related,String reason,UUID actor) {
  jdbc.update("INSERT INTO quality_event(material_id,version,action,assessment_id,related_task_id,reason,actor_id) VALUES(?,?,?,?,?,?,?)",material,version,action,assessment,related,reason,actor);
 }
 /** Readiness boundary for authorized case IDs; callers hold the request lock for mutations. */
 public java.util.Map<UUID,Boolean> diagnosisReadiness(java.util.List<UUID> cases) {
  if(cases.isEmpty()) return java.util.Map.of();
  if(cases.size()>50) throw new IllegalArgumentException("Bounded case projection required");
  String placeholders=String.join(",",java.util.Collections.nCopies(cases.size(),"?"));
  var result=new java.util.HashMap<UUID,Boolean>();
  jdbc.query("SELECT c.id, EXISTS(SELECT 1 FROM material_entity m WHERE m.case_id=c.id AND m.kind='SLIDE' AND m.state='ACTIVE') AND NOT EXISTS(SELECT 1 FROM material_entity m JOIN workflow_quality_projection q ON q.id=m.id WHERE m.case_id=c.id AND m.state='ACTIVE' AND q.state<>'PASS') AND NOT EXISTS(SELECT 1 FROM quality_head h WHERE h.request_id=c.request_id AND h.state='IDENTITY_MISMATCH') AND NOT EXISTS(SELECT 1 FROM cytology_specimen s WHERE s.request_id=c.request_id AND s.qc_state='IDENTITY_MISMATCH') AS ready FROM pathology_case c WHERE c.id IN ("+placeholders+")", r->{ result.put(r.getObject("id",UUID.class),r.getBoolean("ready")); },cases.toArray());
  return java.util.Map.copyOf(result);
 }
 /** Dependency snapshot for an authorized report caller holding the request root lock. */
 public String reportSnapshot(UUID request) {
  return jdbc.queryForObject("SELECT jsonb_build_object('materials',(SELECT jsonb_agg(to_jsonb(m) ORDER BY m.id) FROM material_entity m WHERE m.request_id=?),'quality',(SELECT jsonb_agg(to_jsonb(h) ORDER BY h.material_id) FROM quality_head h WHERE h.request_id=?),'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM technical_task t WHERE t.request_id=?),'cytology',(SELECT jsonb_agg(jsonb_build_object('source',to_jsonb(s),'preparations',(SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM cytology_preparation p WHERE p.specimen_id=s.id)) ORDER BY s.id) FROM cytology_specimen s WHERE s.request_id=?),'staining',(SELECT jsonb_agg(jsonb_build_object('batch',to_jsonb(b),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY e.version) FROM stain_event e WHERE e.batch_id=b.id)) ORDER BY b.id) FROM stain_batch b WHERE b.request_id=?))::text",String.class,request,request,request,request,request);
 }
 public static ApiException blocked() { return new ApiException(HttpStatus.CONFLICT,"QC_QUARANTINED","Quality quarantine requires review"); }
}
