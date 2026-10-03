package com.pis.quality;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.quality.QualityContracts.*;
@Service
public class QualityService {
 private final JdbcTemplate jdbc; private final WorkflowAccess access; private final RequestService requests; private final QualitySubjects subjects; private final QualityGate gate; private final IdempotentCommands commands; private final Validator validator;
 public QualityService(JdbcTemplate jdbc,WorkflowAccess access,RequestService requests,QualitySubjects subjects,QualityGate gate,IdempotentCommands commands,Validator validator) { this.jdbc=jdbc;this.access=access;this.requests=requests;this.subjects=subjects;this.gate=gate;this.commands=commands;this.validator=validator; }
 private QualitySubjects.Subject context(UUID id) { var s=subjects.subject(id); permit(s.scopeId()); return s; }
 private void permit(UUID scope) { try { access.require(scope,Permission.QC); } catch(org.springframework.security.access.AccessDeniedException e) { throw new ApiException(HttpStatus.NOT_FOUND,"QC_NOT_FOUND","Quality resource unavailable"); } }
 private Item item(QualitySubjects.Subject s) { return item(s,gate::head,subjects::subject); }
 private Item item(QualitySubjects.Subject s,java.util.function.Function<UUID,Head> heads,java.util.function.Function<UUID,QualitySubjects.Subject> sources) {
  String state=gate.effective(heads.apply(s.id()),s.version(),s.taskVersion());
  if(!s.state().equals("ACTIVE") || s.taskId()!=null&&!"SIMULATED_DONE".equals(s.taskState())&&List.of("PASS","NOT_ASSESSED").contains(state)) state="INVALIDATED";
  if(s.blockId()!=null) { var b=sources.apply(s.blockId()); String parent=gate.effective(heads.apply(b.id()),b.version(),b.taskVersion()); if(!b.state().equals("ACTIVE")||!List.of("PASS","NOT_ASSESSED").contains(parent)) state="SOURCE_QUARANTINED"; }
  if(s.state().equals("SOURCE_QUARANTINED"))state="SOURCE_QUARANTINED";
  return new Item(s,heads.apply(s.id()),state);
 }
 @Transactional(timeout=10)
 public View view(UUID rid) { permit(requests.detail(rid).scopeId()); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid); permit(requests.detail(rid).scopeId()); var list=subjects.subjects(rid); var sources=list.stream().collect(java.util.stream.Collectors.toMap(QualitySubjects.Subject::id,s->s)); var heads=gate.heads(rid); return new View(rid,list.stream().map(s->item(s,heads::get,sources::get)).toList()); }
 @Transactional(timeout=10)
 public Detail detail(UUID id) {
  var s=context(id); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",s.requestId()); s=context(id);
  var assessments=jdbc.query("SELECT * FROM quality_assessment WHERE material_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100",(r,i)->new Assessment(r.getObject("id",UUID.class),r.getLong("material_version"),r.getObject("task_id",UUID.class),r.getObject("task_version",Long.class),r.getString("standard_version"),r.getString("outcome"),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
  var events=jdbc.query("SELECT * FROM quality_event WHERE material_id=? ORDER BY version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),r.getString("action"),r.getObject("assessment_id",UUID.class),r.getObject("related_task_id",UUID.class),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
  return new Detail(item(s),assessments,events,false);
 }
 public IdempotentCommands.Result assess(UUID id,Assess input,String key) { return execute(id,input,key,"ASSESS"); }
 public IdempotentCommands.Result decide(UUID id,Decision input,String key,String action) {
  if(!List.of("REVOKE","REWORK","EXCEPTION_RELEASE").contains(action)) throw new IllegalArgumentException("Unknown quality command");
  return execute(id,input,key,action);
 }
 private IdempotentCommands.Result execute(UUID id,Object input,String key,String action) {
  var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors); var initial=context(id);
  return commands.execute(initial.hospitalId(),"QC_"+action+"_V1",key,Map.of("material",id,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor) { var s=context(id); if(action.equals("REWORK")) access.require(s.scopeId(),Permission.PROCESS); }
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { authorize(actor); }
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.requestId()); var s=context(id); var h=gate.head(id); long old=h==null?-1:h.version();
    if(action.equals("EXCEPTION_RELEASE")) throw conflict("QC_EXCEPTION_RELEASE_DISABLED");
    long expected=input instanceof Assess a?a.expectedVersion():((Decision)input).expectedVersion(); UUID confirmed=input instanceof Assess a?a.confirmedMaterialId():((Decision)input).confirmedMaterialId();
    if(!id.equals(confirmed)) throw conflict("QC_IDENTITY_MISMATCH"); if(old!=expected) throw conflict("VERSION_CONFLICT");
    if(h!=null&&h.state().equals("IDENTITY_MISMATCH")) throw conflict("QC_IDENTITY_LOCKED");
    long next=old+1; String reason=input instanceof Assess a?a.reason():((Decision)input).reason(); UUID assessment=h==null?null:h.assessmentId(); UUID repair=null;
    if(action.equals("ASSESS")) {
     var a=(Assess)input;
     if(!a.standardVersion().equals("SYN-MATERIAL-QC-1")) throw conflict("QC_STANDARD_UNSUPPORTED");
     if(s.version()!=a.materialVersion()||!Objects.equals(s.taskVersion(),a.taskVersion())) throw conflict("VERSION_CONFLICT");
     if(!s.state().equals("ACTIVE")||s.taskId()!=null&&!"SIMULATED_DONE".equals(s.taskState())) throw conflict("QC_SOURCE_INVALID");
     if(s.blockId()!=null) { var b=subjects.subject(s.blockId()); if(!b.state().equals("ACTIVE")) throw conflict("QC_SOURCE_INVALID"); gate.material(b.id(),b.version(),b.taskVersion()); }
     if(h!=null&&h.state().equals("REWORK_REQUIRED")) throw conflict("QC_REWORK_NEW_MATERIAL_REQUIRED");
     if(jdbc.queryForObject("SELECT count(*) FROM quality_assessment WHERE material_id=?",Long.class,id)>=100) throw conflict("QC_LIMIT_REACHED");
     if(h==null) jdbc.update("INSERT INTO quality_head(material_id,hospital_id,request_id,case_id,patient_id,cassette_id,task_id,block_id,material_version,task_version,state,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",id,s.hospitalId(),s.requestId(),s.caseId(),s.patientId(),s.cassetteId(),s.taskId(),s.blockId(),s.version(),s.taskVersion(),a.outcome().name());
     assessment=UUID.randomUUID();
     jdbc.update("INSERT INTO quality_assessment(id,material_id,material_version,task_id,task_version,standard_version,outcome,reason,actor_id) VALUES(?,?,?,?,?,?,?,?,?)",assessment,id,s.version(),s.taskId(),s.taskVersion(),a.standardVersion(),a.outcome().name(),reason,actor.id());
     if(jdbc.update("UPDATE quality_head SET state=?,version=?,assessment_id=?,material_version=?,task_version=?,repair_task_id=NULL WHERE material_id=? AND version=?",a.outcome().name(),next,assessment,s.version(),s.taskVersion(),id,h==null?0:old)!=1) throw conflict("VERSION_CONFLICT");
    } else {
     if(h==null) throw conflict("QC_NOT_ASSESSED");
     if(action.equals("REWORK")&&(s.taskId()==null||!s.state().equals("ACTIVE")||!List.of("FAIL","PENDING","REVOKED","INVALIDATED").contains(item(s).effectiveState()))) throw conflict("QC_REWORK_UNSUPPORTED");
     if(action.equals("REVOKE")&&List.of("REVOKED","REWORK_REQUIRED").contains(h.state())) throw conflict("QC_STATE_CONFLICT");
     if(jdbc.update("UPDATE quality_head SET state=?,version=version+1 WHERE material_id=? AND version=?",action.equals("REVOKE")?"REVOKED":"REWORK_REQUIRED",id,old)!=1) throw conflict("VERSION_CONFLICT");
     if(action.equals("REWORK")) { repair=subjects.rework(s,reason); if(jdbc.update("UPDATE quality_head SET repair_task_id=? WHERE material_id=? AND version=?",repair,id,next)!=1) throw conflict("VERSION_CONFLICT"); }
    }
    gate.event(id,next,action,assessment,repair,reason,actor.id());
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"QUALITY",id,next),h==null?null:old);
   }
  });
 }
 private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Quality decision requires review"); }
}
