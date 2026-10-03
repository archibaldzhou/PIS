package com.pis.material;
import com.pis.accession.*;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.idempotency.*;
import com.pis.label.*;
import com.pis.specimen.ReceptionService;
import jakarta.validation.Validator;
import java.util.*;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.pis.material.StainContracts.*;
@Service
public class StainService {
 private final JdbcTemplate jdbc;private final RequestService requests;private final ReceptionService reception;private final WorkflowAccess access;private final IdempotentCommands commands;private final LabelService labels;private final AuditRecorder audit;private final Validator validator;private final StainRejectionAudit rejections;
 public StainService(JdbcTemplate jdbc,RequestService requests,ReceptionService reception,WorkflowAccess access,IdempotentCommands commands,LabelService labels,AuditRecorder audit,Validator validator,StainRejectionAudit rejections){this.jdbc=jdbc;this.requests=requests;this.reception=reception;this.access=access;this.commands=commands;this.labels=labels;this.audit=audit;this.validator=validator;this.rejections=rejections;}
 private record Context(UUID hospital,UUID patient,UUID caseId,UUID scope,String number) { }
 private Context context(UUID rid,Action action){var d=requests.detail(rid);boolean qc=action==Action.CONTROL_PASS||action==Action.CONTROL_FAIL||action==Action.REVOKE;String flag=action==null?"(can_request OR can_execute OR can_qc)":action==Action.CREATE||action==Action.ADD?"can_request":qc?"can_qc":"can_execute";
  try{access.require(d.scopeId(),action==null?WorkflowAccess.Permission.READ:qc?WorkflowAccess.Permission.QC:WorkflowAccess.Permission.MATERIAL);}catch(org.springframework.security.access.AccessDeniedException e){throw missing();}
  if(TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForList("SELECT user_id FROM stain_grant WHERE scope_id=? AND user_id=? FOR SHARE",d.scopeId(),access.actor().id());
  if(jdbc.queryForObject("SELECT count(*) FROM stain_grant WHERE scope_id=? AND user_id=? AND qualification='SYN-STAIN-1' AND "+flag+" AND revoked_at IS NULL AND valid_from<=statement_timestamp() AND (valid_until IS NULL OR valid_until>statement_timestamp())",Long.class,d.scopeId(),access.actor().id())!=1)throw missing();var c=reception.receivedSource(rid);return new Context(c.hospitalId(),d.patientId(),c.caseId(),d.scopeId(),c.caseNumber());
 }
 private static final String BATCH_SQL="SELECT b.*,s.kind,s.project_code,s.project_version,s.scheme_code,s.scheme_version,s.metadata FROM stain_batch b JOIN stain_scheme s ON s.id=b.scheme_id ";
 private static final org.springframework.jdbc.core.RowMapper<Batch> BATCH=(r,i)->new Batch(r.getObject("id",UUID.class),r.getString("state"),r.getLong("version"),r.getObject("frozen_version",Long.class),r.getObject("control_id",UUID.class),r.getObject("control_event_id",UUID.class),r.getString("control_reference"),r.getString("reagent_lot"),r.getObject("expires_on",LocalDate.class),r.getObject("rerun_of",UUID.class),r.getString("kind"),r.getString("project_code"),r.getInt("project_version"),r.getString("scheme_code"),r.getInt("scheme_version"),r.getString("metadata"));
 private Batch batch(UUID rid,UUID id){var rows=jdbc.query(BATCH_SQL+"WHERE b.request_id=? AND b.id=?",BATCH,rid,id);if(rows.isEmpty())throw missing();return rows.getFirst();}
 @Transactional(timeout=10) public Detail detail(UUID rid,UUID selected,int page){context(rid,null);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);var c=context(rid,null);if(page<1||page>10000)throw conflict("STAIN_PAGE");jdbc.execute("SET LOCAL statement_timeout='5s'");
  var batches=jdbc.query(BATCH_SQL+"WHERE b.request_id=? ORDER BY b.created_at DESC,b.id LIMIT 100",BATCH,rid);var b=selected==null?null:batch(rid,selected);
  var sources=jdbc.query("SELECT m.*,q.state AS qc_state FROM material_entity m JOIN workflow_quality_projection q ON q.id=m.id WHERE m.request_id=? AND m.case_id=? AND m.kind='SLIDE' ORDER BY m.created_at,m.id LIMIT 100",(r,i)->new SourceView(r.getObject("id",UUID.class),r.getString("display_number"),r.getLong("version"),r.getObject("block_id",UUID.class),r.getString("route"),r.getString("state"),r.getString("qc_state")),rid,c.caseId());
  var orders=b==null?List.<Order>of():jdbc.query("SELECT o.*,sm.output_id,e.id AS result_id,g.state AS effective,CASE WHEN qb.state='DRAFT' THEN 'NOT_FROZEN' WHEN qb.state='FROZEN' THEN 'CONTROL_MISSING' WHEN qb.state='FAIL' THEN 'CONTROL_FAILED' WHEN qb.state='REVOKED' THEN 'CONTROL_REVOKED' WHEN e.id IS NULL THEN 'RESULT_MISSING' WHEN e.technical_qc='TECH_FAIL' THEN 'TECHNICAL_FAILED' WHEN g.state<>'PASS' THEN 'SOURCE_CHANGED' ELSE NULL END AS invalid_reason FROM stain_order o JOIN stain_batch qb ON qb.id=o.batch_id LEFT JOIN stain_member sm ON sm.order_id=o.id LEFT JOIN stain_event e ON e.order_id=o.id AND e.action='RESULT' LEFT JOIN stain_material_gate g ON g.id=sm.output_id WHERE o.batch_id=? ORDER BY o.id LIMIT 20",(r,i)->new Order(r.getObject("id",UUID.class),r.getObject("source_id",UUID.class),r.getLong("source_version"),r.getObject("output_id",UUID.class),r.getObject("result_id",UUID.class),r.getString("effective")==null?"NOT_FROZEN":r.getString("effective"),r.getString("invalid_reason")),b.id());
  var events=b==null?List.<Event>of():jdbc.query("SELECT * FROM stain_event WHERE batch_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),r.getString("action"),r.getObject("order_id",UUID.class),r.getObject("frozen_version",Long.class),r.getObject("control_event_id",UUID.class),r.getString("technical_qc"),r.getString("content"),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("recorded_at",OffsetDateTime.class).toInstant()),b.id(),(page-1)*20);
  audit.append(c.hospital(),"STAIN_READ_V1","PATHOLOGY_REQUEST",rid,null,b==null?0:b.version());return new Detail(rid,c.patient(),c.caseId(),c.number(),batches,b,sources,orders,page,events);
 }
 public IdempotentCommands.Result command(UUID rid,UUID batchId,Action action,Command in,String key){var errors=validator.validate(in);if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);var initial=context(rid,action);
  try{return commands.execute(initial.hospital(),"STAIN_"+action+"_V1",key,Map.of("request",rid,"batch",batchId==null?"":batchId.toString(),"command",in),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){context(rid,action);if(batchId!=null)batch(rid,batchId);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);batch(rid,receipt.resourceId());}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid);var c=context(rid,action);if(!rid.equals(in.confirmedRequestId()))throw missing();var b=batchId==null?null:batch(rid,batchId);long old=b==null?-1:b.version();if(old!=in.expectedVersion())throw conflict("VERSION_CONFLICT");
    if(action!=Action.REVOKE&&action!=Action.CONTROL_FAIL&&jdbc.queryForObject("SELECT (SELECT count(*) FROM quality_head WHERE request_id=? AND state='IDENTITY_MISMATCH')+(SELECT count(*) FROM cytology_specimen WHERE request_id=? AND qc_state='IDENTITY_MISMATCH')",Long.class,rid,rid)>0)throw conflict("QC_QUARANTINED");
    UUID id=b==null?UUID.randomUUID():b.id(),event=UUID.randomUUID();Long frozen=b==null?null:b.frozenVersion();UUID control=b==null?null:b.controlEventId();String state=b==null?"DRAFT":b.state();
    if(action==Action.CREATE){if(b!=null)throw conflict("STAIN_STATE");required(in.metadata());required(in.reagentLot());required(in.controlReference());if(in.kind()==null||!StainPolicy.code(in.projectCode())||!StainPolicy.code(in.schemeCode())||in.projectVersion()==null||in.schemeVersion()==null)throw conflict("STAIN_SCHEME");expiry(in.expiresOn());
     if(in.rerunOf()!=null){var previous=batch(rid,in.rerunOf());if(previous.frozenVersion()==null)throw conflict("STAIN_STATE");}
     if(jdbc.queryForObject("SELECT count(*) FROM stain_batch WHERE request_id=?",Long.class,rid)>=100)throw conflict("STAIN_LIMIT");UUID scheme=UUID.randomUUID();
     jdbc.update("INSERT INTO stain_scheme(id,scope_id,kind,project_code,project_version,scheme_code,scheme_version,metadata) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(scope_id,kind,project_code,project_version,scheme_code,scheme_version) DO NOTHING",scheme,c.scope(),in.kind().name(),in.projectCode(),in.projectVersion(),in.schemeCode(),in.schemeVersion(),in.metadata());
     var schemes=jdbc.queryForList("SELECT id FROM stain_scheme WHERE scope_id=? AND kind=? AND project_code=? AND project_version=? AND scheme_code=? AND scheme_version=? AND metadata=?",UUID.class,c.scope(),in.kind().name(),in.projectCode(),in.projectVersion(),in.schemeCode(),in.schemeVersion(),in.metadata());if(schemes.size()!=1)throw conflict("STAIN_SCHEME");scheme=schemes.getFirst();
     jdbc.update("INSERT INTO stain_batch(id,hospital_id,request_id,case_id,patient_id,scope_id,scheme_id,reagent_lot,expires_on,control_id,control_reference,rerun_of,state,version,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?)",id,c.hospital(),rid,c.caseId(),c.patient(),c.scope(),scheme,in.reagentLot(),in.expiresOn(),UUID.randomUUID(),in.controlReference(),in.rerunOf(),actor.id());add(c,rid,id,in.sources(),actor.id());
    }else{
     if(b==null)throw missing();if(action!=Action.REVOKE&&action!=Action.CONTROL_FAIL)expiry(b.expiresOn());
     switch(action){
      case ADD -> {if(!state.equals("DRAFT"))throw conflict("STAIN_FROZEN");add(c,rid,id,in.sources(),actor.id());}
      case FREEZE -> {if(!state.equals("DRAFT"))throw conflict("STAIN_FROZEN");frozen=old+1;freeze(c,rid,b,frozen,actor.id(),in.reason());state="FROZEN";}
      case CONTROL_PASS,CONTROL_FAIL,REVOKE -> {
       required(in.content());if(!Objects.equals(in.frozenVersion(),frozen)||frozen==null)throw conflict("STAIN_BINDING");if(action==Action.REVOKE?!state.equals("PASS"):!state.equals("FROZEN"))throw conflict("STAIN_STATE");
       if(action==Action.CONTROL_PASS)dependencies(id);if(action==Action.REVOKE&&!Objects.equals(in.controlEventId(),control))throw conflict("STAIN_BINDING");state=action==Action.CONTROL_PASS?"PASS":action==Action.CONTROL_FAIL?"FAIL":"REVOKED";control=event;
      }
      case RESULT -> {required(in.content());if(!state.equals("PASS"))throw conflict("STAIN_CONTROL_REQUIRED");if(!Objects.equals(in.frozenVersion(),frozen)||!Objects.equals(in.controlEventId(),control)||in.technicalQc()==null)throw conflict("STAIN_BINDING");dependencies(id);
       if(jdbc.queryForObject("SELECT count(*) FROM stain_order WHERE batch_id=? AND id=?",Long.class,id,in.orderId())!=1)throw missing();if(jdbc.queryForObject("SELECT count(*) FROM stain_event WHERE order_id=? AND action='RESULT'",Long.class,in.orderId())>0)throw conflict("STAIN_RESULT_EXISTS");
      }
      default -> throw conflict("STAIN_STATE");
     }
     if(jdbc.update("UPDATE stain_batch SET state=?,version=version+1,frozen_version=?,control_event_id=? WHERE id=? AND version=?",state,frozen,control,id,old)!=1)throw conflict("VERSION_CONFLICT");
    }
    jdbc.update("INSERT INTO stain_event(id,batch_id,version,action,order_id,frozen_version,control_event_id,technical_qc,content,reason,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",event,id,old+1,action.name(),action==Action.RESULT?in.orderId():null,frozen,action==Action.RESULT?in.controlEventId():null,action==Action.RESULT?in.technicalQc().name():null,in.content(),in.reason(),actor.id());
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"STAIN_BATCH",id,old+1),old<0?null:old);
   }
  });}catch(ApiException e){rejections.record(initial.hospital(),rid,action.name(),e.code());throw e;}
 }
 private void add(Context c,UUID rid,UUID bid,List<Source> sources,UUID actor){int existing=jdbc.queryForObject("SELECT count(*) FROM stain_order WHERE batch_id=?",Integer.class,bid);try{StainPolicy.quantity(sources.size(),new HashSet<>(sources.stream().map(Source::id).toList()).size(),existing);}catch(IllegalArgumentException e){throw conflict(e.getMessage());}
  for(var s:sources){eligible(rid,c.caseId(),s);if(jdbc.queryForObject("SELECT count(*) FROM stain_order WHERE batch_id=? AND source_id=?",Long.class,bid,s.id())>0)throw conflict("STAIN_QUANTITY");jdbc.update("INSERT INTO stain_order(id,batch_id,hospital_id,request_id,case_id,patient_id,source_id,source_version,initial_dependency,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),bid,c.hospital(),rid,c.caseId(),c.patient(),s.id(),s.version(),snapshot(s.id()),actor);}
 }
 private MaterialContracts.Entity eligible(UUID rid,UUID caseId,Source source){var rows=jdbc.query("SELECT m.* FROM material_entity m JOIN workflow_quality_projection q ON q.id=m.id WHERE m.id=? AND m.request_id=? AND m.case_id=? AND m.kind='SLIDE' AND m.state='ACTIVE' AND m.route<>'STAINED_SLIDE' AND q.state='PASS' AND (m.block_id IS NULL OR EXISTS(SELECT 1 FROM workflow_quality_projection b WHERE b.id=m.block_id AND b.state='PASS'))",MaterialQueries.MAPPER,source.id(),rid,caseId);if(rows.size()!=1)throw conflict("STAIN_SOURCE");var m=rows.getFirst();if(m.version()!=source.version())throw conflict("VERSION_CONFLICT");if(jdbc.queryForObject("SELECT count(*) FROM stain_member WHERE source_id=?",Long.class,m.id())>0)throw conflict("STAIN_QUANTITY");return m;}
 private String snapshot(UUID source){return jdbc.queryForObject("SELECT stain_source_snapshot(?)",String.class,source);}
 private void dependencies(UUID batch){if(jdbc.queryForObject("SELECT count(*) FROM stain_member sm JOIN material_entity om ON om.id=sm.output_id WHERE sm.batch_id=? AND (om.state<>'ACTIVE' OR sm.source_dependency IS DISTINCT FROM stain_source_snapshot(sm.source_id))",Long.class,batch)>0)throw conflict("STAIN_SOURCE_STALE");}
 private void freeze(Context c,UUID rid,Batch batch,long frozen,UUID actor,String reason){var rows=jdbc.queryForList("SELECT id,source_id,source_version,initial_dependency FROM stain_order WHERE batch_id=? ORDER BY id",batch.id());if(rows.isEmpty())throw conflict("STAIN_QUANTITY");if(jdbc.queryForObject("SELECT count(*) FROM material_entity WHERE request_id=?",Long.class,rid)+rows.size()>100)throw conflict("STAIN_LIMIT");
  for(var row:rows){UUID oid=(UUID)row.get("id"),sid=(UUID)row.get("source_id");var m=eligible(rid,c.caseId(),new Source(sid,((Number)row.get("source_version")).longValue()));if(!Objects.equals(row.get("initial_dependency"),snapshot(sid)))throw conflict("STAIN_SOURCE_STALE");UUID output=UUID.randomUUID();
   if(jdbc.update("UPDATE material_entity SET state='VOID',version=version+1 WHERE id=? AND version=? AND state='ACTIVE'",sid,m.version())!=1)throw conflict("VERSION_CONFLICT");
   jdbc.update("INSERT INTO stain_member(order_id,batch_id,source_id,output_id,frozen_version,source_dependency) VALUES(?,?,?,?,?,?)",oid,batch.id(),sid,output,frozen,snapshot(sid));
   String barcode=LabelBarcode.create(output);jdbc.update("INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,record_id,cassette_id,container_id,block_id,source_slide_id,technical_task_id,stain_order_id,created_by) VALUES(?,?,?,?,?,'SLIDE','STAINED_SLIDE','ORIGINAL',?,?,?,?,?,?,?,?,?,?)",output,c.hospital(),c.patient(),rid,c.caseId(),"DEV-S-"+output,barcode,m.recordId(),m.cassetteId(),m.containerId(),m.blockId(),sid,m.technicalTaskId(),oid,actor);
   labels.registerMaterialIdentity(output,barcode);jdbc.update("INSERT INTO material_event(material_id,material_version,action,related_id,reason,actor_id) VALUES(?,?,'STAIN_TRANSFER',?,?,?)",sid,m.version()+1,output,reason,actor);jdbc.update("INSERT INTO material_event(material_id,material_version,action,related_id,reason,actor_id) VALUES(?,0,'CREATE',?,?,?)",output,sid,reason,actor);
  }
 }
 private static void required(String value){if(value==null||value.isBlank())throw conflict("STAIN_TEXT_REQUIRED");}
 private static void expiry(LocalDate d){try{StainPolicy.expiry(d,LocalDate.now(ZoneOffset.UTC));}catch(IllegalArgumentException e){throw conflict(e.getMessage());}}
 private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"STAIN_NOT_FOUND","Stain resource or qualification unavailable");}
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Stain source, batch or control requires review");}
}
