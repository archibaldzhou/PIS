package com.pis.report;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.diagnosis.DiagnosisService;
import com.pis.idempotency.*;
import jakarta.validation.Validator;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.pis.report.AmendmentContracts.*;
@Service
public class AmendmentService {
 private final JdbcTemplate jdbc;private final DiagnosisService diagnosis;private final ReportService reports;private final ReviewService reviews;private final IdempotentCommands commands;private final Validator validator;private final JsonMapper json;
 public AmendmentService(JdbcTemplate jdbc,DiagnosisService diagnosis,ReportService reports,ReviewService reviews,IdempotentCommands commands,Validator validator,JsonMapper json){this.jdbc=jdbc;this.diagnosis=diagnosis;this.reports=reports;this.reviews=reviews;this.commands=commands;this.validator=validator;this.json=json;}
 private record Head(long version,UUID id) { }
 private Head head(UUID id){var rows=jdbc.query("SELECT version,amendment_id FROM report_chain_head WHERE case_id=?",(r,i)->new Head(r.getLong(1),r.getObject(2,UUID.class)),id);return rows.isEmpty()?new Head(0,null):rows.getFirst();}
 private record Signed(UUID id,UUID revision,long draftVersion) { }
 private Signed signed(UUID id){var rows=jdbc.query("SELECT id,revision_id,draft_version FROM report_review_event WHERE case_id=? AND action='SIMULATE_SIGN' ORDER BY version DESC LIMIT 1",(r,i)->new Signed(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3)),id);return rows.isEmpty()?null:rows.getFirst();}
 @Transactional(timeout=10) public Detail detail(UUID id,int page){
  var c=reviews.outputAccess(id);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());c=reviews.outputAccess(id);
  if(page<1||page>10000)throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_PAGE_INVALID","Invalid chain page");
  var h=head(id);var d=reports.current(id);var s=signed(id);boolean pending=s==null||d==null||!s.revision().equals(d.id());
  boolean canCreate=false;try {var owner=diagnosis.reportContext(id);canCreate=owner.ready()&&!pending;}catch(ApiException|org.springframework.security.access.AccessDeniedException e){canCreate=false;}
  var nodes=jdbc.query("SELECT a.*,r.new_signature_id,r.downstream_state FROM report_amendment a LEFT JOIN report_replacement r ON r.amendment_id=a.id WHERE a.case_id=? ORDER BY a.version DESC LIMIT 20 OFFSET ?",(r,i)->new Node(r.getObject("id",UUID.class),r.getLong("version"),Kind.valueOf(r.getString("kind")),r.getObject("parent_id",UUID.class),r.getObject("base_signature_id",UUID.class),r.getObject("base_revision_id",UUID.class),r.getLong("base_draft_version"),r.getObject("start_revision_id",UUID.class),r.getLong("start_version"),r.getObject("actor_id",UUID.class),r.getString("reason"),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("new_signature_id",UUID.class),r.getString("downstream_state")),id,(page-1)*20);
  return new Detail(id,h.version(),c.assignmentVersion(),c.ready(),h.id(),d==null?null:d.id(),d==null?-1:d.version(),s==null?null:s.id(),s==null?null:s.revision(),pending,canCreate,page,nodes);
 }
 @Transactional(timeout=10) public Snapshot snapshot(UUID id,UUID signature){
  var c=reviews.outputAccess(id);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());reviews.outputAccess(id);
  var rows=jdbc.queryForList("SELECT revision_id FROM report_review_event WHERE case_id=? AND id=? AND action='SIMULATE_SIGN'",UUID.class,id,signature);if(rows.isEmpty())throw missing();
  var revision=reports.revision(id,rows.getFirst());var artifacts=jdbc.queryForList("SELECT id FROM report_artifact WHERE case_id=? AND signature_id=?",UUID.class,id,signature);return new Snapshot(id,signature,signed(id).id().equals(signature),revision,artifacts.isEmpty()?null:artifacts.getFirst(),jdbc.queryForObject("SELECT occurred_at FROM report_review_event WHERE case_id=? AND id=?",OffsetDateTime.class,id,signature).toInstant());
 }
 public IdempotentCommands.Result create(UUID id,Create input,String key){
  var errors=validator.validate(input);if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);
  var initial=diagnosis.reportContext(id);reviews.outputAccess(id);
  return commands.execute(initial.hospitalId(),"REPORT_AMENDMENT_CREATE_V1",key,Map.of("case",id,"command",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){diagnosis.reportContext(id);reviews.outputAccess(id);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.requestId());var c=diagnosis.reportContext(id);reviews.outputAccess(id);var h=head(id);var s=signed(id);var d=reports.current(id);
    if(!id.equals(input.confirmedCaseId()))throw conflict("REPORT_IDENTITY_MISMATCH");
    if(h.version()!=input.expectedVersion()||c.assignmentVersion()!=input.assignmentVersion())throw conflict("VERSION_CONFLICT");
    if(!c.ready())throw conflict("DIAGNOSIS_NOT_READY");
    if(s==null||d==null||!s.revision().equals(d.id()))throw conflict("REPORT_AMENDMENT_PENDING");
    if(!s.id().equals(input.baseSignatureId())||!s.revision().equals(input.baseRevisionId())||s.draftVersion()!=input.baseDraftVersion())throw conflict("VERSION_CONFLICT");
    UUID branch=UUID.randomUUID(),revision=UUID.randomUUID();long next=d.version()+1;
    jdbc.update("INSERT INTO report_revision(id,case_id,version,template_code,template_version,fields,assignment_version,author_id,reason) VALUES(?,?,?,?,?,?::jsonb,?,?,?)",revision,id,next,d.templateCode(),d.templateVersion(),json.writeValueAsString(d.fields()),c.assignmentVersion(),actor.id(),input.reason());
    jdbc.update("INSERT INTO report_amendment(id,case_id,version,kind,parent_id,base_signature_id,base_revision_id,base_draft_version,start_revision_id,start_version,actor_id,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",branch,id,h.version()+1,input.kind().name(),h.id(),s.id(),s.revision(),s.draftVersion(),revision,next,actor.id(),input.reason());
    if(h.id()==null)jdbc.update("INSERT INTO report_chain_head(case_id,version,amendment_id) VALUES(?,1,?)",id,branch);
    else if(jdbc.update("UPDATE report_chain_head SET version=version+1,amendment_id=? WHERE case_id=? AND version=?",branch,id,h.version())!=1)throw conflict("VERSION_CONFLICT");
    if(jdbc.update("UPDATE report_draft SET version=version+1,revision_id=? WHERE case_id=? AND version=?",revision,id,d.version())!=1)throw conflict("VERSION_CONFLICT");
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_AMENDMENT",branch,h.version()+1),h.version());
   }
  });
 }
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Report chain requires current predecessor and authorization");}
 private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"REPORT_OUTPUT_NOT_FOUND","Report version unavailable");}
}
