package com.pis.report;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.diagnosis.DiagnosisService;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.pis.report.ReviewContracts.*;
@Service
public class ReviewService {
 private final JdbcTemplate jdbc;private final DiagnosisService diagnosis;private final ReportService reports;private final CurrentActor actors;private final IdempotentCommands commands;private final Validator validator;
 public ReviewService(JdbcTemplate jdbc,DiagnosisService diagnosis,ReportService reports,CurrentActor actors,IdempotentCommands commands,Validator validator) { this.jdbc=jdbc;this.diagnosis=diagnosis;this.reports=reports;this.actors=actors;this.commands=commands;this.validator=validator; }
 private record Grant(boolean review,boolean sign,String snapshot) { }
 private Grant grant(UUID scope,UUID actor) {
  diagnosis.qualificationSnapshot(scope,actor);
  if(TransactionSynchronizationManager.isActualTransactionActive()) jdbc.queryForList("SELECT user_id FROM report_review_grant WHERE scope_id=? AND user_id=? FOR SHARE",scope,actor);
  String base=diagnosis.qualificationSnapshot(scope,actor); // Recheck validity after the grant lock wait.
  var rows=jdbc.query("SELECT can_review,can_simulate_sign,to_jsonb(g)::text snapshot FROM report_review_grant g WHERE scope_id=? AND user_id=? AND revoked_at IS NULL AND valid_from<=statement_timestamp() AND (valid_until IS NULL OR valid_until>statement_timestamp())",(r,i)->new Grant(r.getBoolean(1),r.getBoolean(2),base+":"+r.getString(3)),scope,actor);
  return base.equals("UNAVAILABLE")||rows.isEmpty()?new Grant(false,false,"UNAVAILABLE"):rows.getFirst();
 }
 private DiagnosisService.ReviewContext authorize(UUID id,Action action) {
  var c=diagnosis.reviewContext(id);var g=grant(c.scopeId(),actors.require().id());
  if(action==null?!(g.review()||g.sign()):action==Action.SIMULATE_SIGN?!g.sign():!g.review()) throw missing();
  policy(c.scopeId());return c;
 }
 private Policy policy(UUID scope) {
  var rows=jdbc.query("SELECT * FROM report_review_policy WHERE scope_id=?",(r,i)->new Policy(r.getString("code"),r.getBoolean("separate_author_review"),r.getBoolean("separate_review_sign")),scope);
  if(rows.isEmpty()) throw conflict("REPORT_REVIEW_DISABLED");return rows.getFirst();
 }
 private record Stored(Event event,String dependencies,String actorSnapshot) { }
 private Stored map(java.sql.ResultSet r,int i) throws java.sql.SQLException { return new Stored(new Event(r.getObject("id",UUID.class),r.getLong("version"),r.getObject("revision_id",UUID.class),r.getLong("draft_version"),Action.valueOf(r.getString("action")),r.getObject("actor_id",UUID.class),r.getObject("review_id",UUID.class),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),r.getString("dependency_snapshot"),r.getString("actor_snapshot")); }
 private Stored head(UUID id) { var rows=jdbc.query("SELECT e.* FROM report_review_head h JOIN report_review_event e ON e.id=h.event_id WHERE h.case_id=?",this::map,id);return rows.isEmpty()?null:rows.getFirst(); }
 private String token(DiagnosisService.ReviewContext c,ReportContracts.Revision draft,Policy p) { return digest(c.dependencies()+":"+c.ready()+":"+(draft==null?"NONE":draft.id()+":"+draft.templateCode()+":"+draft.templateVersion())+":"+p); }
 private static String digest(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
 private boolean valid(Stored h,String token,UUID scope) {
  if(h==null||h.event().action()!=Action.APPROVE||!h.dependencies().equals(token)) return false;
  var g=grant(scope,h.event().actorId());return g.review()&&g.snapshot().equals(h.actorSnapshot());
 }
 @Transactional(timeout=10) public Detail detail(UUID id) {
  var c=authorize(id,null);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());c=authorize(id,null);
  var draft=reports.current(id);var p=policy(c.scopeId());String token=token(c,draft,p);var h=head(id);var g=grant(c.scopeId(),actors.require().id());
  boolean reviewed=valid(h,token,c.scopeId());String state=h==null?"DRAFT":h.event().action()==Action.APPROVE?(reviewed?"APPROVED":"STALE"):h.event().action()==Action.RETURN?"RETURNED":"SIMULATED_SIGNED";
  boolean ready=c.ready()&&reviewed;
  var events=jdbc.query("SELECT * FROM report_review_event WHERE case_id=? ORDER BY version DESC LIMIT 100",this::map,id).stream().map(Stored::event).toList();
  return new Detail(id,c.patientId(),c.number(),c.assignmentVersion(),h==null?-1:h.event().version(),state,ready,g.review(),g.sign(),p,token,draft,events);
 }
 @Transactional(timeout=10) public History history(UUID id,int page) {
  var c=authorize(id,null);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());authorize(id,null);
  if(page<1||page>10000) throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_PAGE_INVALID","Invalid review history page");
  return new History(id,page,jdbc.query("SELECT * FROM report_review_event WHERE case_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",this::map,id,(page-1)*20).stream().map(Stored::event).toList());
 }
 public IdempotentCommands.Result decide(UUID id,Decision input,String key,Action action) {
  var errors=validator.validate(input);if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors);
  if(input.simulationAcknowledged()!=(action==Action.SIMULATE_SIGN)) throw conflict("REPORT_SIMULATION_CONFIRMATION");
  var initial=authorize(id,action);
  return commands.execute(initial.hospitalId(),"REPORT_REVIEW_"+action+"_V1",key,Map.of("case",id,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor) { ReviewService.this.authorize(id,action); }
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { authorize(actor); }
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.requestId());var c=ReviewService.this.authorize(id,action);
    var draft=reports.current(id);var p=policy(c.scopeId());var h=head(id);long version=h==null?-1:h.event().version();String snapshot=token(c,draft,p);
    if(!id.equals(input.confirmedCaseId())) throw conflict("REPORT_IDENTITY_MISMATCH");
    if(version!=input.expectedVersion()||c.assignmentVersion()!=input.assignmentVersion()||draft==null||!draft.id().equals(input.revisionId())||draft.version()!=input.draftVersion()||!draft.templateCode().equals(input.templateCode())||draft.templateVersion()!=input.templateVersion()||!snapshot.equals(input.dependencyToken())) throw conflict("VERSION_CONFLICT");
    if(h!=null&&h.event().action()==Action.SIMULATE_SIGN) throw conflict("REPORT_SIMULATED_FROZEN");
    if(!c.ready()||action!=Action.RETURN&&draft.fields().get("diagnosis").stringValue().isBlank()) throw conflict("REPORT_REVIEW_NOT_READY");
    if(action==Action.APPROVE&&h!=null&&h.event().action()==Action.RETURN&&h.event().revisionId().equals(draft.id())) throw conflict("REPORT_REVISION_REQUIRED");
    if(action==Action.APPROVE&&p.separateAuthorReview()&&draft.authorId().equals(actor.id())) throw conflict("REPORT_SEPARATION_REQUIRED");
    if(action==Action.SIMULATE_SIGN&&(!valid(h,snapshot,c.scopeId())||p.separateReviewSign()&&h.event().actorId().equals(actor.id()))) throw conflict("REPORT_REVIEW_STALE");
    UUID event=UUID.randomUUID();long next=version+1;var g=grant(c.scopeId(),actor.id());
    jdbc.update("INSERT INTO report_review_event(id,case_id,version,revision_id,draft_version,action,actor_id,actor_snapshot,dependency_snapshot,dependency_evidence,review_id,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",event,id,next,draft.id(),draft.version(),action.name(),actor.id(),g.snapshot(),snapshot,c.dependencies()+":"+p+":"+draft.id(),action==Action.SIMULATE_SIGN?h.event().id():null,input.reason());
    if(h==null) jdbc.update("INSERT INTO report_review_head(case_id,version,event_id) VALUES(?,0,?)",id,event);
    else if(jdbc.update("UPDATE report_review_head SET version=version+1,event_id=? WHERE case_id=? AND version=?",event,id,version)!=1) throw conflict("VERSION_CONFLICT");
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_REVIEW",id,next),version<0?null:version);
   }
  });
 }
 private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"REPORT_REVIEW_NOT_FOUND","Synthetic review unavailable"); }
 private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Synthetic review requires current authorization and snapshot"); }
}
