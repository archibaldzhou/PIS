package com.pis.diagnosis;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import com.pis.quality.QualityGate;
import jakarta.validation.Validator;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.pis.diagnosis.DiagnosisContracts.*;
@Service
public class DiagnosisService {
 private final JdbcTemplate jdbc; private final WorkflowAccess access; private final RequestService requests; private final QualityGate quality; private final IdempotentCommands commands; private final Validator validator;
 public DiagnosisService(JdbcTemplate jdbc,WorkflowAccess access,RequestService requests,QualityGate quality,IdempotentCommands commands,Validator validator) { this.jdbc=jdbc;this.access=access;this.requests=requests;this.quality=quality;this.commands=commands;this.validator=validator; }
 private record Rights(boolean assign,boolean diagnose) { }
 private static final String ELIGIBLE="""
  FROM diagnosis_grant d JOIN workflow_grant g ON g.user_id=d.user_id AND g.scope_id=d.scope_id
  JOIN app_user u ON u.id=d.user_id JOIN workflow_scope s ON s.id=d.scope_id
  WHERE d.scope_id=? AND u.enabled AND u.synthetic_only AND s.enabled AND g.can_read
  AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())
  AND d.revoked_at IS NULL AND d.valid_from<=statement_timestamp() AND (d.valid_until IS NULL OR d.valid_until>statement_timestamp())
  AND d.qualification='SYN-DIAG-ASSIGNMENT-1'
  """;
 private Rights rights(UUID scope,UUID user) {
  if(TransactionSynchronizationManager.isActualTransactionActive()) {
   jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",user);
   jdbc.queryForList("SELECT user_id FROM workflow_grant WHERE user_id=? AND scope_id=? FOR SHARE",user,scope);
   jdbc.queryForList("SELECT user_id FROM diagnosis_grant WHERE user_id=? AND scope_id=? FOR SHARE",user,scope);
  }
  var values=jdbc.query("SELECT d.can_assign,d.can_diagnose "+ELIGIBLE+" AND d.user_id=?",(r,i)->new Rights(r.getBoolean(1),r.getBoolean(2)),scope,user);
  return values.isEmpty()?new Rights(false,false):values.getFirst();
 }
 private Rights permit(UUID scope,Action action) {
  try { access.require(scope,WorkflowAccess.Permission.READ); } catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); }
  var r=rights(scope,access.actor().id());
  if(action==null?!(r.assign()||r.diagnose()):action==Action.ASSIGN?!r.assign():!r.diagnose()) throw missing();
  return r;
 }
 private void target(UUID scope,UUID id) { if(id==null||!rights(scope,id).diagnose()) throw conflict("DIAGNOSIS_TARGET_UNAVAILABLE"); }
 private record Context(UUID caseId,UUID request,UUID scope,UUID hospital,UUID patient,String number,String requestState,String state,long version,UUID owner) { }
 private static final String SOURCE="SELECT c.id,c.request_id,w.scope_id,c.hospital_id,r.patient_id,c.case_number,w.state request_state,coalesce(d.state,'UNASSIGNED') state,coalesce(d.version,-1) version,d.owner_id FROM pathology_case c JOIN pathology_request r ON r.id=c.request_id JOIN request_workflow w ON w.request_id=r.id LEFT JOIN diagnosis_assignment d ON d.case_id=c.id";
 private static final org.springframework.jdbc.core.RowMapper<Context> MAPPER=(r,i)->new Context(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("scope_id",UUID.class),r.getObject("hospital_id",UUID.class),r.getObject("patient_id",UUID.class),r.getString("case_number"),r.getString("request_state"),r.getString("state"),r.getLong("version"),r.getObject("owner_id",UUID.class));
 private Context context(UUID id,Action action) {
  try { access.actor(); } catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); }
  var rows=jdbc.query(SOURCE+" WHERE c.id=?",MAPPER,id); if(rows.isEmpty()) throw missing(); var c=rows.getFirst();
  permit(c.scope(),action); requests.detail(c.request()); return c;
 }
 private Item item(Context c,boolean ready) { return new Item(c.caseId(),c.request(),c.patient(),c.number(),c.state(),c.version(),c.owner(),c.requestState().equals("RECEIVED")&&ready); }
 @Transactional(timeout=10)
 public Page list(UUID scope,State state,int page,int size) {
  permit(scope,null); if(page<1||page>10000||size<1||size>50) throw new ApiException(HttpStatus.BAD_REQUEST,"DIAGNOSIS_PAGE_INVALID","Invalid page");
  jdbc.execute("SET LOCAL statement_timeout='5s'");
  String filter=" WHERE w.scope_id=? AND (?='ALL' OR coalesce(d.state,'UNASSIGNED')=?)";
  String sql="WITH filtered AS MATERIALIZED ("+SOURCE+filter+"), totals AS (SELECT count(*) total FROM filtered), items AS (SELECT * FROM filtered ORDER BY case_number,id LIMIT ? OFFSET ?) SELECT totals.total,items.* FROM totals LEFT JOIN items ON true ORDER BY case_number,id";
  var contexts=new ArrayList<Context>(); final long[] total={0};
  jdbc.query(sql,r->{total[0]=r.getLong("total");if(r.getObject("id")!=null) contexts.add(MAPPER.mapRow(r,0));},scope,state.name(),state.name(),size,(page-1)*size);
  var ready=quality.diagnosisReadiness(contexts.stream().map(Context::caseId).toList()); permit(scope,null);
  return new Page(total[0],page,size,contexts.stream().map(c->item(c,ready.getOrDefault(c.caseId(),false))).toList());
 }
 @Transactional(timeout=10)
 public Detail detail(UUID id) {
  var c=context(id,null); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.request()); c=context(id,null);
  var rights=permit(c.scope(),null);
  var candidates=jdbc.query("SELECT u.id,u.display_name "+ELIGIBLE+" AND d.can_diagnose ORDER BY u.id LIMIT 100",(r,i)->new Candidate(r.getObject(1,UUID.class),r.getString(2)),c.scope());
  var events=jdbc.query("SELECT * FROM diagnosis_event WHERE case_id=? ORDER BY version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),r.getString("action"),r.getObject("actor_id",UUID.class),r.getObject("previous_owner_id",UUID.class),r.getObject("next_owner_id",UUID.class),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
  return new Detail(item(c,quality.diagnosisReadiness(List.of(id)).getOrDefault(id,false)),access.actor().id(),rights.assign(),rights.diagnose(),candidates,events);
 }
 public IdempotentCommands.Result decide(UUID id,Decision input,String key,Action action) {
  var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors);
  if(action==Action.CLAIM?input.targetUserId()!=null:input.targetUserId()==null) throw new ApiException(HttpStatus.BAD_REQUEST,"DIAGNOSIS_TARGET_INVALID","Invalid target for action");
  var initial=context(id,action);
  return commands.execute(initial.hospital(),"DIAG_"+action+"_V1",key,Map.of("case",id,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor) { var c=context(id,action); if(action!=Action.CLAIM) target(c.scope(),input.targetUserId()); }
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { authorize(actor); }
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.request()); var c=context(id,action);
    if(!id.equals(input.confirmedCaseId())) throw conflict("DIAGNOSIS_IDENTITY_MISMATCH");
    if(c.version()!=input.expectedVersion()) throw conflict("VERSION_CONFLICT");
    UUID owner=action==Action.CLAIM?actor.id():input.targetUserId(); target(c.scope(),owner);
    if(!item(c,quality.diagnosisReadiness(List.of(id)).getOrDefault(id,false)).ready()) throw conflict("DIAGNOSIS_NOT_READY");
    if(action==Action.ASSIGN&&!c.state().equals("UNASSIGNED") || action==Action.CLAIM&&!(c.state().equals("UNASSIGNED")||c.state().equals("ASSIGNED")&&actor.id().equals(c.owner())) || action==Action.TRANSFER&&!(c.state().equals("ACTIVE")&&actor.id().equals(c.owner())&&!owner.equals(actor.id()))) throw conflict("DIAGNOSIS_STATE_CONFLICT");
    String state=action==Action.CLAIM?"ACTIVE":"ASSIGNED"; long version=c.version()+1;
    if(c.version()==-1) jdbc.update("INSERT INTO diagnosis_assignment(case_id,hospital_id,request_id,state,owner_id,version) VALUES(?,?,?,?,?,0)",id,c.hospital(),c.request(),state,owner);
    else if(jdbc.update("UPDATE diagnosis_assignment SET state=?,owner_id=?,version=version+1 WHERE case_id=? AND version=?",state,owner,id,c.version())!=1) throw conflict("VERSION_CONFLICT");
    jdbc.update("INSERT INTO diagnosis_event(case_id,version,action,actor_id,previous_owner_id,next_owner_id,reason) VALUES(?,?,?,?,?,?,?)",id,version,action.name(),actor.id(),c.owner(),owner,input.reason());
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"DIAGNOSIS_ASSIGNMENT",id,version),c.version()<0?null:c.version());
   }
  });
 }
 /** Public report boundary: current qualified ACTIVE holder only; caller rechecks after the request lock. */
 public record ReportContext(UUID caseId,UUID requestId,UUID hospitalId,UUID patientId,String number,long assignmentVersion,boolean ready) { }
 public ReportContext reportContext(UUID id) {
  var c=context(id,Action.CLAIM);
  if(!c.state().equals("ACTIVE")||!access.actor().id().equals(c.owner())) throw missing();
  return new ReportContext(id,c.request(),c.hospital(),c.patient(),c.number(),c.version(),item(c,quality.diagnosisReadiness(List.of(id)).getOrDefault(id,false)).ready());
 }
 private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"DIAGNOSIS_NOT_FOUND","Diagnosis resource unavailable"); }
 private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Diagnosis command requires review"); }
}
