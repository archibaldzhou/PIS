package com.pis.report;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.idempotency.*;
import jakarta.validation.Validator;
import java.util.*;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.report.DeliveryContracts.*;
@Service
public class DeliveryService {
 private final JdbcTemplate jdbc;private final ReviewService reviews;private final OutputService outputs;private final IdempotentCommands commands;private final Validator validator;private final AuditRecorder audit;private final DeliveryRejectionAudit rejections;
 public DeliveryService(JdbcTemplate jdbc,ReviewService reviews,OutputService outputs,IdempotentCommands commands,Validator validator,AuditRecorder audit,DeliveryRejectionAudit rejections){this.jdbc=jdbc;this.reviews=reviews;this.outputs=outputs;this.commands=commands;this.validator=validator;this.audit=audit;this.rejections=rejections;}
 private static final String SELECT="SELECT d.id,d.artifact_id,d.destination,d.predecessor,a.signature_id,a.revision_id,a.sha256,o.* FROM report_delivery d JOIN report_artifact a ON a.id=d.artifact_id JOIN report_delivery_outbox o ON o.delivery_id=d.id WHERE d.case_id=?";
 private Item map(java.sql.ResultSet r,int n)throws java.sql.SQLException {var lease=r.getObject("lease_until",OffsetDateTime.class);return new Item(r.getObject("id",UUID.class),r.getObject("artifact_id",UUID.class),r.getObject("signature_id",UUID.class),r.getObject("revision_id",UUID.class),r.getString("sha256"),r.getString("destination"),r.getObject("predecessor",UUID.class),r.getLong("version"),r.getString("state"),r.getInt("attempts"),r.getObject("attempt_id",UUID.class),lease==null?null:lease.toInstant(),r.getObject("next_at",OffsetDateTime.class).toInstant());}
 private Item item(UUID c,UUID id){var rows=jdbc.query(SELECT+" AND d.id=?",this::map,c,id);if(rows.isEmpty())throw missing();return rows.getFirst();}
 @Transactional(timeout=10) public Detail detail(UUID id,int page){var c=reviews.outputAccess(id);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());reviews.outputAccess(id);if(page<1||page>10000)throw bad();audit.append(c.hospitalId(),"DELIVERY_QUERY_V1","PATHOLOGY_CASE",id,null,0);return new Detail(id,com.pis.integration.CaAdapter.unavailable(false).status().name(),jdbc.query(SELECT+" ORDER BY d.created_at DESC,d.id LIMIT 20 OFFSET ?",this::map,id,(page-1)*20));}
 @Transactional(timeout=10) public List<Event> history(UUID id,UUID delivery,int page){var c=reviews.outputAccess(id);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());reviews.outputAccess(id);item(id,delivery);if(page<1||page>10000)throw bad();audit.append(c.hospitalId(),"DELIVERY_HISTORY_V1","REPORT_DELIVERY",delivery,null,0);return jdbc.query("SELECT * FROM report_delivery_event WHERE delivery_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",(r,i)->new Event(r.getLong("version"),r.getString("action"),r.getString("state"),r.getObject("attempt_id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),delivery,(page-1)*20);}
 private void validate(Command c){var e=validator.validate(c);if(!e.isEmpty())throw new jakarta.validation.ConstraintViolationException(e);}
 private void bind(UUID id,Command c,OutputContracts.Artifact a){if(!id.equals(c.confirmedCaseId())||!a.id().equals(c.artifactId())||!a.signatureId().equals(c.signatureId())||!a.revisionId().equals(c.revisionId())||!a.sha256().equals(c.sha256())||!c.destination().equals("LOCAL_SIM"))throw conflict("DELIVERY_BINDING");}
 private void current(UUID id,UUID artifact){var f=reviews.outputFrozenLocked(id);var a=outputs.historical(id,artifact).artifact();if(!f.dependenciesCurrent()||!f.signatureId().equals(a.signatureId()))throw conflict("REPORT_OUTPUT_STALE");}
 public IdempotentCommands.Result enqueue(UUID id,Command input,String key){return execute(id,null,input,key,null);}
 public IdempotentCommands.Result step(UUID id,UUID delivery,Command input,String key,Action action){return execute(id,delivery,input,key,action);}
 private IdempotentCommands.Result execute(UUID id,UUID delivery,Command input,String key,Action action){
  validate(input);var context=reviews.outputAccess(id);
  try { return commands.execute(context.hospitalId(),action==null?"DELIVERY_QUEUE_V1":"DELIVERY_"+action+"_V1",key,Map.of("case",id,"delivery",delivery==null?"NEW":delivery,"command",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){reviews.outputAccess(id);if(delivery!=null)item(id,delivery);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);item(id,action==null?receipt.resourceId():delivery);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",context.requestId());reviews.outputAccess(id);var artifact=outputs.historical(id,input.artifactId()).artifact();bind(id,input,artifact);
    if(action==null){
     if(input.expectedVersion()!=0||input.attemptId()!=null)throw bad();current(id,artifact.id());
     var existing=jdbc.queryForList("SELECT id FROM report_delivery WHERE artifact_id=? AND destination=?",UUID.class,artifact.id(),input.destination());if(!existing.isEmpty())return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_DELIVERY",existing.getFirst(),0),null);
     var previous=jdbc.queryForList("SELECT old_signature_id FROM report_replacement WHERE case_id=? AND new_signature_id=?",UUID.class,id,artifact.signatureId());UUID next=UUID.randomUUID();
     jdbc.update("INSERT INTO report_delivery(id,case_id,artifact_id,destination,predecessor,created_by,reason) VALUES(?,?,?,?,?,?,?)",next,id,artifact.id(),input.destination(),previous.isEmpty()?null:previous.getFirst(),actor.id(),input.reason());jdbc.update("INSERT INTO report_delivery_outbox(delivery_id) VALUES(?)",next);event(next,0,"QUEUE","QUEUED",null,actor.id(),input.reason());return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_DELIVERY",next,0),null);
    }
    var old=item(id,delivery);if(!old.artifactId().equals(input.artifactId())||old.version()!=input.expectedVersion())throw conflict("VERSION_CONFLICT");
    Instant now=jdbc.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class).toInstant();String state=old.state();int attempts=old.attempts();UUID attempt=old.attemptId();Instant lease=null,next=old.nextAt();
    switch(action){
     case CLAIM -> {if(!DeliveryPolicy.canClaim(state,attempts,next,now)||input.attemptId()!=null)throw conflict("DELIVERY_NOT_READY");attempts++;attempt=UUID.randomUUID();lease=now.plusSeconds(30);state="ATTEMPTING";}
     case RECEIVE,ACK -> {
      if(!DeliveryPolicy.active(state,attempt,input.attemptId(),old.leaseUntil(),now))throw conflict("DELIVERY_STALE_ATTEMPT");lease=old.leaseUntil();
      if(action==Action.RECEIVE){
       current(id,artifact.id());
       if(jdbc.queryForObject("SELECT count(*) FROM report_local_inbox WHERE delivery_id=?",Long.class,delivery)==0){
        var receiver=jdbc.queryForList("SELECT a.signature_id FROM report_local_receiver r JOIN report_delivery d ON d.id=r.delivery_id JOIN report_artifact a ON a.id=d.artifact_id WHERE r.case_id=?",UUID.class,id);
        UUID previous=receiver.isEmpty()?null:receiver.getFirst();
        if(!Objects.equals(previous,old.predecessor())){state="REJECTED";lease=null;}
        else {
         jdbc.update("INSERT INTO report_local_inbox(delivery_id,case_id,artifact_id,signature_id,revision_id,sha256,destination,pdf) SELECT ?,case_id,id,signature_id,revision_id,sha256,?,pdf FROM report_artifact WHERE case_id=? AND id=?",delivery,input.destination(),id,artifact.id());
         jdbc.update("INSERT INTO report_local_receiver(case_id,delivery_id) VALUES(?,?) ON CONFLICT(case_id) DO UPDATE SET delivery_id=EXCLUDED.delivery_id",id,delivery);
        }
       }
      }else {if(!received(id,old))throw conflict("DELIVERY_ACK_MISMATCH");state="ACKED";lease=null;}
     }
     case FAIL,TIMEOUT,POISON -> {if(!state.equals("ATTEMPTING")||!Objects.equals(attempt,input.attemptId())||action==Action.TIMEOUT&&now.isBefore(old.leaseUntil()))throw conflict("DELIVERY_STALE_ATTEMPT");state=DeliveryPolicy.failureState(attempts,action==Action.POISON);next=now.plusSeconds(DeliveryPolicy.backoffSeconds(attempts));}
     case RECONCILE -> {if(!state.equals("ACKED")||!Objects.equals(attempt,input.attemptId()))throw conflict("DELIVERY_NOT_READY");state=received(id,old)&&jdbc.queryForObject("SELECT count(*) FROM report_local_receiver WHERE case_id=? AND delivery_id=?",Long.class,id,delivery)==1?"RECONCILED":"REJECTED";}
    }
    if(jdbc.update("UPDATE report_delivery_outbox SET version=version+1,state=?,attempts=?,attempt_id=?,lease_until=?,next_at=? WHERE delivery_id=? AND version=?",state,attempts,attempt,lease==null?null:OffsetDateTime.ofInstant(lease,ZoneOffset.UTC),OffsetDateTime.ofInstant(next,ZoneOffset.UTC),delivery,old.version())!=1)throw conflict("VERSION_CONFLICT");event(delivery,old.version()+1,action.name(),state,attempt,actor.id(),input.reason());return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_DELIVERY",delivery,old.version()+1),old.version());
   }
  }); } catch(ApiException e){rejections.record(context.hospitalId(),id,action==null?"QUEUE":action.name(),e.code());throw e;}
 }
 private boolean received(UUID id,Item i){return jdbc.queryForObject("SELECT count(*) FROM report_local_inbox WHERE case_id=? AND delivery_id=? AND artifact_id=? AND signature_id=? AND revision_id=? AND sha256=? AND destination=?",Long.class,id,i.id(),i.artifactId(),i.signatureId(),i.revisionId(),i.sha256(),i.destination())==1;}
 private void event(UUID d,long v,String a,String s,UUID attempt,UUID actor,String reason){jdbc.update("INSERT INTO report_delivery_event(id,delivery_id,version,action,state,attempt_id,actor_id,reason) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),d,v,a,s,attempt,actor,reason);}
 private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"DELIVERY_NOT_FOUND","Synthetic delivery unavailable");}
 private static ApiException bad(){return new ApiException(HttpStatus.BAD_REQUEST,"DELIVERY_INPUT","Invalid synthetic delivery input");}
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Synthetic delivery requires current version and evidence");}
}
