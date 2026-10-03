package com.pis.frozen;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.diagnosis.DiagnosisService;
import com.pis.idempotency.*;
import com.pis.quality.QualityGate;
import com.pis.report.AmendmentService;
import jakarta.validation.Validator;
import java.util.*;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.pis.frozen.FrozenContracts.*;
@Service
public class FrozenService {
 private final JdbcTemplate jdbc;private final WorkflowAccess access;private final RequestService requests;private final DiagnosisService diagnosis;private final QualityGate quality;private final AmendmentService reports;private final IdempotentCommands commands;private final AuditRecorder audit;private final Validator validator;private final FrozenRejectionAudit rejections;
 public FrozenService(JdbcTemplate jdbc,WorkflowAccess access,RequestService requests,DiagnosisService diagnosis,QualityGate quality,AmendmentService reports,IdempotentCommands commands,AuditRecorder audit,Validator validator,FrozenRejectionAudit rejections){this.jdbc=jdbc;this.access=access;this.requests=requests;this.diagnosis=diagnosis;this.quality=quality;this.reports=reports;this.commands=commands;this.audit=audit;this.validator=validator;this.rejections=rejections;}
 private record Context(UUID id,UUID request,UUID hospital,UUID scope,String number,long requestVersion,String requestState) { }
 private static final String ELIGIBLE="""
 FROM frozen_grant f JOIN app_user u ON u.id=f.user_id JOIN workflow_grant g ON g.user_id=f.user_id AND g.scope_id=f.scope_id
 JOIN workflow_scope s ON s.id=f.scope_id JOIN diagnosis_grant d ON d.user_id=f.user_id AND d.scope_id=f.scope_id
 WHERE f.scope_id=? AND u.enabled AND u.synthetic_only AND s.enabled AND g.can_read AND d.can_diagnose
 AND d.qualification='SYN-DIAG-ASSIGNMENT-1' AND f.qualification='SYN-FROZEN-1'
 AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())
 AND d.revoked_at IS NULL AND d.valid_from<=statement_timestamp() AND (d.valid_until IS NULL OR d.valid_until>statement_timestamp())
 AND f.revoked_at IS NULL AND f.valid_from<=statement_timestamp() AND (f.valid_until IS NULL OR f.valid_until>statement_timestamp())
 """;
 private Candidate rights(UUID scope,UUID user){
  if(user==null)return null;
  if(TransactionSynchronizationManager.isActualTransactionActive()){
   diagnosis.qualificationSnapshot(scope,user);
   jdbc.queryForList("SELECT user_id FROM frozen_grant WHERE scope_id=? AND user_id=? FOR SHARE",scope,user);
  }
  var rows=jdbc.query("SELECT u.id,u.display_name,f.can_record,f.can_review,f.can_qc "+ELIGIBLE+" AND u.id=?",(r,i)->new Candidate(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3),r.getBoolean(4),r.getBoolean(5)),scope,user);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private void permit(UUID scope,Action action){
  try{access.require(scope,false);}catch(org.springframework.security.access.AccessDeniedException e){throw missing();}
  var r=rights(scope,access.actor().id());
  boolean allowed=r!=null&&(action==null?(r.record()||r.review()||r.qc()):action==Action.REVIEW?r.review():List.of(Action.QC_PASS,Action.QC_FAIL,Action.IDENTITY_MISMATCH).contains(action)?r.qc():r.record());
  if(!allowed)throw missing();
 }
 private Context context(UUID id,Action action){
  access.actor();
  var rows=jdbc.query("SELECT c.id,c.request_id,c.hospital_id,c.case_number,w.scope_id,r.version,w.state FROM pathology_case c JOIN pathology_request r ON r.id=c.request_id JOIN request_workflow w ON w.request_id=r.id WHERE c.id=?",(r,i)->new Context(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(5,UUID.class),r.getString(4),r.getLong(6),r.getString(7)),id);
  if(rows.isEmpty())throw missing();var c=rows.getFirst();permit(c.scope(),action);requests.detail(c.request());return c;
 }
 private Head head(UUID id){var rows=jdbc.query("SELECT * FROM frozen_case WHERE case_id=?",(r,i)->new Head(r.getObject("id",UUID.class),r.getObject("material_id",UUID.class),r.getObject("container_id",UUID.class),r.getString("site"),r.getLong("version"),r.getObject("owner_id",UUID.class),r.getBoolean("owner_active"),r.getObject("received_id",UUID.class),r.getObject("prepared_id",UUID.class),r.getObject("revision_id",UUID.class),r.getObject("review_id",UUID.class),r.getObject("qc_id",UUID.class),r.getString("qc_state"),r.getString("stage")),id);return rows.isEmpty()?null:rows.getFirst();}
 private static final org.springframework.jdbc.core.RowMapper<Event> EVENT=(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),Action.valueOf(r.getString("action")),r.getObject("actor_id",UUID.class),r.getObject("target_user_id",UUID.class),r.getObject("result_id",UUID.class),r.getObject("related_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant(),r.getObject("recorded_at",OffsetDateTime.class).toInstant(),r.getString("zone_id"),r.getInt("offset_seconds"),r.getString("reason"),r.getString("content"),r.getString("dependency"),r.getObject("routine_signature_id",UUID.class),r.getObject("routine_revision_id",UUID.class),r.getString("routine_template_code"),r.getObject("routine_template_version",Integer.class),r.getString("comparison"),r.getString("communication_method"));
 private Event event(Head h,UUID id){if(id==null)throw conflict("FROZEN_REFERENCE_REQUIRED");var rows=jdbc.query("SELECT * FROM frozen_event WHERE frozen_id=? AND id=?",EVENT,h.id(),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
 private boolean gate(Context c){return c.requestState().equals("RECEIVED")&&jdbc.queryForObject("SELECT count(*) FROM cytology_specimen WHERE request_id=? AND qc_state='IDENTITY_MISMATCH'",Long.class,c.request())==0&&jdbc.queryForObject("SELECT count(*) FROM quality_head WHERE request_id=? AND state<>'PASS'",Long.class,c.request())==0&&jdbc.queryForObject("SELECT count(*) FROM workflow_quality_projection WHERE request_id=? AND state NOT IN ('PASS','NOT_ASSESSED')",Long.class,c.request())==0;}
 private String person(UUID scope,UUID user){var r=rights(scope,user);if(r==null)return "UNAVAILABLE";return diagnosis.qualificationSnapshot(scope,user)+jdbc.queryForObject("SELECT to_jsonb(f)::text FROM frozen_grant f WHERE scope_id=? AND user_id=?",String.class,scope,user);}
 private String dependency(Context c,Head h,UUID reviewer){return FrozenPolicy.digest(c.requestVersion()+":"+h.ownerId()+":"+h.ownerActive()+":"+h.receivedId()+":"+h.preparedId()+":"+h.qcId()+":"+h.qcState()+":"+h.revisionId()+":"+person(c.scope(),h.ownerId())+":"+person(c.scope(),event(h,h.revisionId()).actorId())+":"+person(c.scope(),reviewer)+":"+quality.reportSnapshot(c.request()));}
 private boolean validReview(Context c,Head h){if(h==null||h.reviewId()==null||!h.ownerActive()||!h.qcState().equals("PASS")||!gate(c))return false;var e=event(h,h.reviewId());var r=rights(c.scope(),e.actorId());var owner=rights(c.scope(),h.ownerId());var author=rights(c.scope(),event(h,h.revisionId()).actorId());return r!=null&&r.review()&&owner!=null&&owner.record()&&author!=null&&author.record()&&Objects.equals(e.resultId(),h.revisionId())&&e.dependency().equals(dependency(c,h,e.actorId()));}
 @Transactional(timeout=10) public List<CaseItem> cases(UUID request){var d=requests.detail(request);permit(d.scopeId(),null);jdbc.execute("SET LOCAL statement_timeout='5s'");audit.append(access.require(d.scopeId(),false).hospitalId(),"FROZEN_CASES_READ_V1","PATHOLOGY_REQUEST",request,null,d.version());return jdbc.query("SELECT id,case_number FROM pathology_case WHERE request_id=? ORDER BY id LIMIT 100",(r,i)->new CaseItem(r.getObject(1,UUID.class),r.getString(2)),request);}
 @Transactional(timeout=10) public Detail detail(UUID id,int page){
  var c=context(id,null);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.request());c=context(id,null);if(page<1||page>10000)throw bad("FROZEN_PAGE_INVALID");jdbc.execute("SET LOCAL statement_timeout='5s'");var h=head(id);
  var sources=jdbc.query("SELECT c.id,d.site FROM specimen_container c JOIN request_container_detail d ON d.container_id=c.id WHERE c.case_id=? ORDER BY c.id LIMIT 100",(r,i)->new Source(r.getObject(1,UUID.class),r.getString(2)),id);
  var candidates=jdbc.query("SELECT u.id,u.display_name,f.can_record,f.can_review,f.can_qc "+ELIGIBLE+" ORDER BY u.id LIMIT 100",(r,i)->new Candidate(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3),r.getBoolean(4),r.getBoolean(5)),c.scope());
  var events=h==null?List.<Event>of():jdbc.query("SELECT * FROM frozen_event WHERE frozen_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",EVENT,h.id(),(page-1)*20);
  audit.append(c.hospital(),"FROZEN_READ_V1","FROZEN_CASE",id,null,h==null?0:h.version());return new Detail(id,c.number(),access.actor().id(),h,gate(c),validReview(c,h),h==null?null:event(h,h.receivedId()).occurredAt(),h==null||h.preparedId()==null?null:event(h,h.preparedId()).occurredAt(),h==null||h.preparedId()==null?null:Duration.between(event(h,h.receivedId()).occurredAt(),event(h,h.preparedId()).occurredAt()).getSeconds(),sources,candidates,page,events,h==null||h.revisionId()==null?null:dependency(c,h,access.actor().id()));
 }
 public IdempotentCommands.Result command(UUID id,Action action,Command input,String key){
  var errors=validator.validate(input);if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);
  var initial=context(id,action);
  try { return commands.execute(initial.hospital(),"FROZEN_"+action+"_V1",key,Map.of("case",id,"input",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){context(id,action);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.request());var c=context(id,action);var h=head(id);
    if(!id.equals(input.confirmedCaseId()))throw conflict("FROZEN_IDENTITY_MISMATCH");if((h==null?-1:h.version())!=input.expectedVersion())throw conflict("VERSION_CONFLICT");
    Instant now=jdbc.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class).toInstant();Instant occurred;
    try{occurred=FrozenPolicy.time(input.occurredAt(),input.zoneId(),now);}catch(IllegalArgumentException e){throw bad(e.getMessage());}
    if(!gate(c)&&!List.of(Action.QC_FAIL,Action.IDENTITY_MISMATCH).contains(action))throw conflict("QC_QUARANTINED");
    UUID eventId=UUID.randomUUID(),frozenId=h==null?UUID.randomUUID():h.id();long version=h==null?0:h.version()+1;
    UUID result=null,related=null,target=null,routineRevision=null;String digest=null,template=null,comparison=null;Integer templateVersion=null;
    if(action==Action.RECEIVE){
     if(h!=null)throw conflict("FROZEN_ALREADY_EXISTS");
     if(input.containerId()==null||input.site()==null||input.site().isBlank())throw bad("FROZEN_SOURCE_REQUIRED");
     if(jdbc.queryForObject("SELECT count(*) FROM specimen_container WHERE id=? AND case_id=?",Long.class,input.containerId(),id)!=1)throw missing();
     if(jdbc.queryForObject("SELECT count(*) FROM cytology_specimen WHERE container_id=?",Long.class,input.containerId())>0)throw conflict("CYTOLOGY_LEDGER_REQUIRED");
     jdbc.update("INSERT INTO frozen_case(id,hospital_id,request_id,case_id,container_id,material_id,site,version,owner_id,owner_active,received_id,qc_state) VALUES(?,?,?,?,?,?,?,0,?,true,?,'NOT_ASSESSED')",frozenId,c.hospital(),c.request(),id,input.containerId(),UUID.randomUUID(),input.site(),actor.id(),eventId);
    }else{
     if(h==null)throw conflict("FROZEN_NOT_RECEIVED");
     if(h.qcState().equals("IDENTITY_MISMATCH"))throw conflict("QC_QUARANTINED");
     boolean ownerAction=!List.of(Action.REVIEW,Action.QC_PASS,Action.QC_FAIL,Action.IDENTITY_MISMATCH,Action.READBACK,Action.CONFIRM).contains(action);
     if(ownerAction&&(!h.ownerId().equals(actor.id())||!h.ownerActive()&&action!=Action.CLAIM))throw missing();
     if(action!=Action.CORRECT_TIME)after(occurred,event(h,h.receivedId()).occurredAt());
     switch(action){
      case PREPARE -> {if(h.preparedId()!=null)throw conflict("FROZEN_STAGE_CONFLICT");}
      case CORRECT_TIME -> {
       related=input.relatedId();if(related==null||!Objects.equals(related,h.receivedId())&&!Objects.equals(related,h.preparedId()))throw conflict("FROZEN_TIME_REFERENCE");
       Instant receive=Objects.equals(related,h.receivedId())?occurred:event(h,h.receivedId()).occurredAt();
       Instant prepare=Objects.equals(related,h.preparedId())?occurred:h.preparedId()==null?null:event(h,h.preparedId()).occurredAt();
       if(prepare!=null)after(prepare,receive);
       OffsetDateTime first=jdbc.queryForObject("SELECT min(occurred_at) FROM frozen_event WHERE frozen_id=? AND action NOT IN ('RECEIVE','PREPARE','CORRECT_TIME')",OffsetDateTime.class,h.id());
       Instant earliest=first==null?null:first.toInstant();
       if(earliest!=null)after(earliest,prepare==null?receive:prepare);
      }
      case QC_PASS,QC_FAIL,IDENTITY_MISMATCH -> {if(h.preparedId()==null)throw conflict("FROZEN_NOT_PREPARED");after(occurred,event(h,h.preparedId()).occurredAt());}
      case DRAFT -> {ready(h);nonblank(input.content());after(occurred,event(h,h.qcId()).occurredAt());if(h.revisionId()!=null)after(occurred,event(h,h.revisionId()).occurredAt());result=eventId;}
      case REVIEW -> {
       ready(h);result=exactRevision(h,input.resultId());var draft=event(h,result);if(draft.actorId().equals(actor.id()))throw conflict("FROZEN_SEPARATION_REQUIRED");
       var author=rights(c.scope(),draft.actorId());var owner=rights(c.scope(),h.ownerId());if(author==null||!author.record()||owner==null||!owner.record())throw conflict("FROZEN_QUALIFICATION_REVOKED");
       after(occurred,draft.occurredAt());after(occurred,event(h,h.qcId()).occurredAt());digest=dependency(c,h,actor.id());if(!digest.equals(input.reviewToken()))throw conflict("FROZEN_REVIEW_INVALIDATED");
      }
      case TRANSFER -> {target=input.targetUserId();var r=rights(c.scope(),target);if(r==null||!r.record()||target.equals(actor.id()))throw missing();}
      case CLAIM -> {if(h.ownerActive())throw conflict("FROZEN_STAGE_CONFLICT");}
      case COMMUNICATE -> {
       requireReview(c,h);result=exactRevision(h,input.resultId());target=input.targetUserId();var recipient=rights(c.scope(),target);if(recipient==null||!recipient.record())throw missing();related=h.reviewId();after(occurred,event(h,related).occurredAt());
      }
      case READBACK,CONFIRM -> {
       requireReview(c,h);result=exactRevision(h,input.resultId());related=input.relatedId();var communication=event(h,related);
       if(communication.action()!=Action.COMMUNICATE||!Objects.equals(communication.resultId(),result)||!Objects.equals(communication.relatedId(),h.reviewId())||!actor.id().equals(communication.targetUserId()))throw conflict("FROZEN_COMMUNICATION_MISMATCH");
       target=communication.targetUserId();nonblank(input.content());after(occurred,communication.occurredAt());
       var prior=jdbc.query("SELECT * FROM frozen_event WHERE frozen_id=? AND related_id=? AND action IN ('READBACK','CONFIRM')",EVENT,h.id(),related);
       if(prior.stream().anyMatch(e->e.action()==action))throw conflict("FROZEN_ALREADY_RECORDED");
       if(action==Action.CONFIRM){var readback=prior.stream().filter(e->e.action()==Action.READBACK).findFirst().orElseThrow(()->conflict("FROZEN_READBACK_REQUIRED"));after(occurred,readback.occurredAt());}
      }
      case LINK_ROUTINE -> {
       requireReview(c,h);result=exactRevision(h,input.resultId());nonblank(input.content());if(input.routineSignatureId()==null||input.comparison()==null)throw bad("FROZEN_LINK_REQUIRED");
       var snapshot=reports.snapshot(id,input.routineSignatureId());after(occurred,snapshot.frozenAt());routineRevision=snapshot.revision().id();template=snapshot.revision().templateCode();templateVersion=snapshot.revision().templateVersion();comparison=input.comparison();related=h.reviewId();after(occurred,event(h,related).occurredAt());
      }
      default -> throw conflict("FROZEN_STAGE_CONFLICT");
     }
    }
    // Typed immutable events are both result revisions and append-only communication/history records.
    jdbc.update("INSERT INTO frozen_event(id,frozen_id,case_id,version,action,actor_id,target_user_id,result_id,related_id,occurred_at,zone_id,offset_seconds,reason,content,dependency,routine_signature_id,routine_revision_id,routine_template_code,routine_template_version,comparison,communication_method) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",eventId,frozenId,id,version,action.name(),actor.id(),target,result,related,input.occurredAt(),input.zoneId(),input.occurredAt().getOffset().getTotalSeconds(),input.reason(),input.content(),digest,action==Action.LINK_ROUTINE?input.routineSignatureId():null,routineRevision,template,templateVersion,comparison,List.of(Action.COMMUNICATE,Action.READBACK,Action.CONFIRM).contains(action)?"LOCAL_SIMULATION":null);
    if(h!=null){
     UUID received=h.receivedId(),prepared=h.preparedId(),revision=h.revisionId(),review=h.reviewId(),qc=h.qcId(),owner=h.ownerId();String qcState=h.qcState();boolean active=h.ownerActive();
     switch(action){
      case PREPARE -> prepared=eventId;
      case CORRECT_TIME -> {if(Objects.equals(related,h.receivedId()))received=eventId;else prepared=eventId;review=null;}
      case QC_PASS,QC_FAIL,IDENTITY_MISMATCH -> {qc=eventId;qcState=action==Action.QC_PASS?"PASS":action==Action.QC_FAIL?"FAIL":"IDENTITY_MISMATCH";review=null;}
      case DRAFT -> {revision=eventId;review=null;}
      case REVIEW -> review=eventId;
      case TRANSFER -> {owner=target;active=false;review=null;}
      case CLAIM -> {active=true;review=null;}
      default -> { }
     }
     if(jdbc.update("UPDATE frozen_case SET version=version+1,owner_id=?,owner_active=?,received_id=?,prepared_id=?,revision_id=?,review_id=?,qc_id=?,qc_state=? WHERE case_id=? AND version=?",owner,active,received,prepared,revision,review,qc,qcState,id,h.version())!=1)throw conflict("VERSION_CONFLICT");
    }
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"FROZEN_CASE",id,version),h==null?null:h.version());
   }
  }); } catch(ApiException e){rejections.record(initial.hospital(),id,action.name(),e.code());throw e;}
 }
 private void ready(Head h){if(h.preparedId()==null||!h.qcState().equals("PASS")||!h.ownerActive())throw conflict("FROZEN_NOT_READY");}
 private UUID exactRevision(Head h,UUID id){if(id==null||!id.equals(h.revisionId()))throw conflict("VERSION_CONFLICT");return id;}
 private void requireReview(Context c,Head h){if(!validReview(c,h))throw conflict("FROZEN_REVIEW_INVALIDATED");}
 private void after(Instant value,Instant predecessor){try{FrozenPolicy.after(value,predecessor);}catch(IllegalArgumentException e){throw conflict(e.getMessage());}}
 private void nonblank(String value){if(value.isBlank())throw bad("FROZEN_TEXT_REQUIRED");}
 private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"FROZEN_NOT_FOUND","Frozen resource unavailable");}
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Frozen operation requires current identity, qualification and version");}
 private static ApiException bad(String code){return new ApiException(HttpStatus.BAD_REQUEST,code,"Invalid frozen input");}
}
