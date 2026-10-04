package com.pis.report;

import com.pis.ai.*;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.diagnosis.DiagnosisService;
import com.pis.idempotency.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SyntheticDecisionService {
 public enum Action { ACCEPT_REFERENCE, REJECT, DEFER }
 public record Command(@NotNull UUID confirmedCaseId,@NotNull UUID resultId,@NotNull UUID targetRevisionId,@NotNull @Min(0) Long reportVersion,@NotNull @Min(0) Long assignmentVersion,@NotNull @Min(-1) Long expectedVersion,@NotNull Action action,@NotBlank @Size(max=500) String reason,@AssertTrue boolean confirmed){}
 public record Event(UUID id,UUID caseId,UUID resultId,long version,UUID targetRevisionId,long targetVersion,long assignmentVersion,Action action,String reason,UUID actorId,OffsetDateTime recordedAt,SyntheticResultPackage.Binding binding,String artifactHash,UUID adoptedRevisionId){}
 public record View(UUID caseId,UUID resultId,long version,ReportContracts.Revision target,long assignmentVersion,boolean ready,String invalidReason,AiResultService.Result source,List<Event> history,int page,boolean executionAllowed){}
 private final JdbcTemplate jdbc;private final DiagnosisService diagnosis;private final ReportService reports;private final AiResultService results;private final SyntheticWorkerMode mode;private final IdempotentCommands commands;private final AuditRecorder audit;private final Validator validator;private final TransactionTemplate tx;private final JsonMapper json;
 public SyntheticDecisionService(JdbcTemplate jdbc,DiagnosisService diagnosis,ReportService reports,AiResultService results,SyntheticWorkerMode mode,IdempotentCommands commands,AuditRecorder audit,Validator validator,PlatformTransactionManager manager,JsonMapper json){this.jdbc=jdbc;this.diagnosis=diagnosis;this.reports=reports;this.results=results;this.mode=mode;this.commands=commands;this.audit=audit;this.validator=validator;this.json=json;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Synthetic reference requires explicit review of current result and report versions");}
 private DiagnosisService.ReportContext context(UUID id){mode.require();var c=diagnosis.reportContext(id);if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",c.requestId());return diagnosis.reportContext(id);}
 private boolean frozen(UUID id){return jdbc.queryForObject("SELECT count(*) FROM report_review_head h JOIN report_review_event e ON e.id=h.event_id JOIN report_draft d ON d.case_id=h.case_id AND d.revision_id=e.revision_id WHERE h.case_id=? AND e.action='SIMULATE_SIGN'",Long.class,id)>0;}
 private long version(UUID id,UUID result){var rows=jdbc.queryForList("SELECT version FROM report_result_head WHERE case_id=? AND result_id=?",Long.class,id,result);return rows.isEmpty()?-1:rows.getFirst();}
 private Event map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Event(r.getObject("id",UUID.class),r.getObject("case_id",UUID.class),r.getObject("result_id",UUID.class),r.getLong("version"),r.getObject("target_revision_id",UUID.class),r.getLong("target_version"),r.getLong("assignment_version"),Action.valueOf(r.getString("action")),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("recorded_at",OffsetDateTime.class),json.readValue(r.getString("binding"),SyntheticResultPackage.Binding.class),r.getString("artifact_hash"),r.getObject("adopted_revision_id",UUID.class));}
 public View view(UUID id,UUID result,int page){if(page<1||page>100)throw conflict("AI_DECISION_PAGE");return tx.execute(t->{var c=context(id);var source=results.reference(c.requestId(),result);if(!source.result().binding().input().caseId().equals(id))throw conflict("AI_DECISION_BINDING");var target=reports.current(id);String invalid=source.invalidReason();if(invalid==null&&!c.ready())invalid="REPORT_QC_NOT_READY";if(invalid==null&&target==null)invalid="REPORT_MISSING";if(invalid==null&&frozen(id))invalid="REPORT_FROZEN";audit.append(c.hospitalId(),"AI_DECISION_READ_V1","REPORT_DRAFT",id,null,Math.max(0,version(id,result)));return new View(id,result,version(id,result),target,c.assignmentVersion(),invalid==null,invalid,source.result(),jdbc.query("SELECT * FROM report_result_decision WHERE case_id=? AND result_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",this::map,id,result,(page-1)*20),page,false);});}
 private AiResultService.Metadata authorized(UUID id,UUID result){var c=context(id);if(!c.ready())throw conflict("DIAGNOSIS_NOT_READY");var m=results.metadata(c.requestId(),result);if(m.executionAllowed()||!m.result().binding().input().caseId().equals(id))throw conflict("AI_DECISION_BINDING");return m;}
 public IdempotentCommands.Result decide(UUID id,Command input,String key){var errors=validator.validate(input);if(!errors.isEmpty())throw new ConstraintViolationException(errors);if(!id.equals(input.confirmedCaseId()))throw conflict("AI_DECISION_BINDING");var initial=context(id);
  return commands.execute(initial.hospitalId(),"AI_RESULT_DECISION_V1",key,Map.of("case",id,"command",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){var c=context(id);if(!c.ready())throw conflict("DIAGNOSIS_NOT_READY");var ref=results.reference(c.requestId(),input.resultId());if(ref.invalidReason()!=null)throw conflict("AI_RESULT_INVALIDATED");if(!ref.result().binding().input().caseId().equals(id))throw conflict("AI_DECISION_BINDING");}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorized(id,input.resultId());if(jdbc.queryForObject("SELECT count(*) FROM report_result_decision WHERE id=? AND case_id=? AND result_id=?",Long.class,receipt.resourceId(),id,input.resultId())!=1)throw conflict("AI_DECISION_BINDING");}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){var c=context(id);var m=authorized(id,input.resultId());var old=reports.current(id);
    if(old==null||!old.id().equals(input.targetRevisionId())||old.version()!=input.reportVersion()||c.assignmentVersion()!=input.assignmentVersion())throw conflict("AI_DECISION_CONFLICT");
    if(frozen(id))throw conflict("REPORT_SIMULATED_FROZEN");
    jdbc.update("INSERT INTO report_result_head(case_id,result_id) VALUES(?,?) ON CONFLICT DO NOTHING",id,input.resultId());
    if(jdbc.update("UPDATE report_result_head SET version=version+1 WHERE case_id=? AND result_id=? AND version=? AND version<99",id,input.resultId(),input.expectedVersion())!=1)throw conflict("AI_DECISION_CONFLICT");
    if(input.action()==Action.ACCEPT_REFERENCE&&jdbc.queryForObject("SELECT count(*) FROM report_result_decision WHERE case_id=? AND result_id=? AND version=? AND action='ACCEPT_REFERENCE'",Long.class,id,input.resultId(),input.expectedVersion())>0)throw conflict("AI_DECISION_ALREADY_ACCEPTED");
    UUID adopted=null;
    if(input.action()==Action.ACCEPT_REFERENCE){reports.appendDraft(id,new ReportContracts.Save(old.version(),c.assignmentVersion(),id,old.templateCode(),old.templateVersion(),old.fields(),"Synthetic non-diagnostic reference: "+input.reason()),actor);adopted=reports.current(id).id();}
    UUID event=UUID.randomUUID();long next=input.expectedVersion()+1;
    jdbc.update("INSERT INTO report_result_decision(id,case_id,result_id,version,target_revision_id,target_version,assignment_version,action,reason,actor_id,binding,artifact_hash,adopted_revision_id) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",event,id,input.resultId(),next,old.id(),old.version(),c.assignmentVersion(),input.action().name(),input.reason(),actor.id(),json.writeValueAsString(m.result().binding()),m.result().artifactHash(),adopted);
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"SYNTHETIC_REPORT_DECISION",event,next),input.expectedVersion()<0?null:input.expectedVersion());
   }
  });
 }
 public enum ReviewAction { ACKNOWLEDGE, DEFER }
 public record ReviewCommand(@NotNull UUID confirmedCaseId,@NotNull UUID decisionId,@NotNull @Min(-1) Long expectedVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String snapshotHash,@NotNull ReviewAction action,@NotBlank @Size(max=500) String reason,@AssertTrue boolean confirmed){}
 public record ImpactSnapshot(UUID decisionId,UUID resultId,String epoch,AiResultService.Validity validity,UUID currentRevisionId,long assignmentVersion,boolean reportFrozen,boolean reportReady){}
 public record ImpactReview(UUID id,long version,String snapshotHash,ReviewAction action,String reason,UUID actorId,OffsetDateTime recordedAt){}
 public record Impact(UUID caseId,Event decision,ImpactSnapshot snapshot,long version,boolean sourceValid,boolean consumable,boolean pendingReview,List<ImpactReview> history,boolean executionAllowed){}
 private Event event(UUID id,UUID decision){var rows=jdbc.query("SELECT * FROM report_result_decision WHERE case_id=? AND id=?",this::map,id,decision);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"AI_DECISION_NOT_FOUND","Synthetic reference unavailable");return rows.getFirst();}
 private long impactVersion(UUID decision){var rows=jdbc.queryForList("SELECT version FROM report_impact_head WHERE decision_id=?",Long.class,decision);return rows.isEmpty()?-1:rows.getFirst();}
 private Impact impactNow(UUID id,UUID decision){var c=context(id);var e=event(id,decision);var validity=results.validity(c.requestId(),e.resultId());var target=reports.current(id);boolean signed=frozen(id);String epoch=com.pis.scan.ScanFormat.sha(json.writeValueAsString(List.of(e.id(),validity.epoch(),target==null?"NONE":target.id(),c.assignmentVersion(),signed,c.ready())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var snapshot=new ImpactSnapshot(e.id(),e.resultId(),epoch,validity,target==null?null:target.id(),c.assignmentVersion(),signed,c.ready());
  var history=jdbc.query("SELECT * FROM report_impact_review WHERE decision_id=? ORDER BY version DESC LIMIT 100",(r,i)->new ImpactReview(r.getObject("id",UUID.class),r.getLong("version"),r.getString("snapshot_hash"),ReviewAction.valueOf(r.getString("action")),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("recorded_at",OffsetDateTime.class)),decision);
  boolean current=validity.reasons().isEmpty()&&validity.reference().invalidReason()==null;
  boolean acknowledged=!history.isEmpty()&&history.getFirst().snapshotHash().equals(epoch)&&history.getFirst().action()==ReviewAction.ACKNOWLEDGE;
  return new Impact(id,e,snapshot,impactVersion(decision),current,current&&c.ready()&&e.action()==Action.ACCEPT_REFERENCE&&target!=null&&target.id().equals(e.adoptedRevisionId()),!current&&!acknowledged,history,false);
 }
 public Impact impact(UUID id,UUID decision){return tx.execute(t->{var v=impactNow(id,decision);audit.append(context(id).hospitalId(),"AI_IMPACT_READ_V1","SYNTHETIC_REPORT_DECISION",decision,null,v.decision().version());return v;});}
 /** Explicit current consumption: history responses are never a consumable reference. */
 public Event consumeReference(UUID id,UUID decision){return tx.execute(t->{var v=impactNow(id,decision);if(!v.consumable())throw conflict("AI_REFERENCE_INVALIDATED");authorized(id,v.decision().resultId());audit.append(context(id).hospitalId(),"AI_REFERENCE_CONSUME_V1","SYNTHETIC_REPORT_DECISION",decision,null,v.decision().version());return v.decision();});}
 public IdempotentCommands.Result reviewImpact(UUID id,UUID decision,ReviewCommand input,String key){var errors=validator.validate(input);if(!errors.isEmpty())throw new ConstraintViolationException(errors);if(!id.equals(input.confirmedCaseId())||!decision.equals(input.decisionId()))throw conflict("AI_DECISION_BINDING");var initial=context(id);
  return commands.execute(initial.hospitalId(),"AI_IMPACT_REVIEW_V1",key,Map.of("case",id,"decision",decision,"command",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){context(id);event(id,decision);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){var v=impactNow(id,decision);if(!v.snapshot().epoch().equals(input.snapshotHash()))throw conflict("AI_IMPACT_CHANGED");if(jdbc.queryForObject("SELECT count(*) FROM report_impact_review WHERE id=? AND decision_id=?",Long.class,receipt.resourceId(),decision)!=1)throw conflict("AI_DECISION_BINDING");}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){var v=impactNow(id,decision);if(v.sourceValid()||!v.snapshot().epoch().equals(input.snapshotHash()))throw conflict("AI_IMPACT_CHANGED");jdbc.update("INSERT INTO report_impact_head(decision_id) VALUES(?) ON CONFLICT DO NOTHING",decision);if(jdbc.update("UPDATE report_impact_head SET version=version+1 WHERE decision_id=? AND version=? AND version<99",decision,input.expectedVersion())!=1)throw conflict("AI_IMPACT_CONFLICT");UUID eventId=UUID.randomUUID();long next=input.expectedVersion()+1;jdbc.update("INSERT INTO report_impact_review(id,decision_id,case_id,result_id,version,snapshot_hash,snapshot,action,reason,actor_id) VALUES(?,?,?,?,?,?,?::jsonb,?,?,?)",eventId,decision,id,v.decision().resultId(),next,input.snapshotHash(),json.writeValueAsString(v.snapshot()),input.action().name(),input.reason(),actor.id());return new IdempotentCommands.Mutation(new CommandReceipt(200,"SYNTHETIC_IMPACT_REVIEW",eventId,next),input.expectedVersion()<0?null:input.expectedVersion());}
  });
 }

}
