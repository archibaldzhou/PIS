package com.pis.material;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.idempotency.*;
import com.pis.label.LabelBarcode;
import com.pis.label.LabelService;
import com.pis.specimen.ReceptionService;
import jakarta.validation.Validator;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.pis.material.CytologyContracts.*;
@Service
public class CytologyService {
 private final JdbcTemplate jdbc;private final RequestService requests;private final ReceptionService reception;private final WorkflowAccess access;private final IdempotentCommands commands;private final LabelService labels;private final AuditRecorder audit;private final Validator validator;private final CytologyRejectionAudit rejections;
 public CytologyService(JdbcTemplate jdbc,RequestService requests,ReceptionService reception,WorkflowAccess access,IdempotentCommands commands,LabelService labels,AuditRecorder audit,Validator validator,CytologyRejectionAudit rejections){this.rejections=rejections;this.jdbc=jdbc;this.requests=requests;this.reception=reception;this.access=access;this.commands=commands;this.labels=labels;this.audit=audit;this.validator=validator;}
 private record Context(UUID hospital,UUID patient,UUID caseId,UUID scope,String number,int quantity) { }
 private Context context(UUID rid,UUID container,Action action){
  var d=requests.detail(rid);boolean qc=List.of(Action.QC_PASS,Action.QC_FAIL,Action.IDENTITY_MISMATCH).contains(action==null?Action.REGISTER:action);
  try{access.require(d.scopeId(),action==null?WorkflowAccess.Permission.READ:qc?WorkflowAccess.Permission.QC:WorkflowAccess.Permission.MATERIAL);}catch(org.springframework.security.access.AccessDeniedException e){throw missing();}
  if(TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForList("SELECT user_id FROM cytology_grant WHERE scope_id=? AND user_id=? FOR SHARE",d.scopeId(),access.actor().id());
  if(jdbc.queryForObject("SELECT count(*) FROM cytology_grant WHERE scope_id=? AND user_id=? AND qualification='SYN-CYTOLOGY-1' AND (? AND (can_qc OR can_prepare) OR ? AND can_qc OR NOT ? AND can_prepare) AND revoked_at IS NULL AND valid_from<=statement_timestamp() AND (valid_until IS NULL OR valid_until>statement_timestamp())",Long.class,d.scopeId(),access.actor().id(),action==null,qc,qc)!=1)throw missing();
  var c=d.containers().stream().filter(v->v.id().equals(container)).findFirst().orElseThrow(CytologyService::missing);var received=reception.receivedSource(rid);
  return new Context(received.hospitalId(),d.patientId(),received.caseId(),d.scopeId(),received.caseNumber(),c.materialQuantity());
 }
 private Specimen specimen(UUID container){var rows=jdbc.query("SELECT * FROM cytology_specimen WHERE container_id=?",(r,i)->new Specimen(r.getObject("id",UUID.class),container,r.getObject("patient_id",UUID.class),r.getObject("case_id",UUID.class),r.getInt("initial_quantity"),r.getInt("remaining"),r.getLong("version"),r.getString("qc_state"),r.getLong("qc_version"),r.getString("sample_description")),container);return rows.isEmpty()?null:rows.getFirst();}
 private static final org.springframework.jdbc.core.RowMapper<Preparation> PREPARATION=(r,i)->new Preparation(r.getObject("id",UUID.class),Path.valueOf(r.getString("path")),r.getString("metadata"),r.getInt("transferred"),r.getLong("source_qc_version"),r.getObject("repeat_of",UUID.class),r.getString("state"),r.getLong("version"));
 @Transactional(timeout=10) public Detail detail(UUID rid,UUID container,int page){
  context(rid,container,null);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);var c=context(rid,container,null);if(page<1||page>10000)throw conflict("CYTOLOGY_PAGE");jdbc.execute("SET LOCAL statement_timeout='5s'");var s=specimen(container);
  var prep=s==null?List.<Preparation>of():jdbc.query("SELECT * FROM cytology_preparation WHERE specimen_id=? ORDER BY created_at DESC,id LIMIT 100",PREPARATION,s.id());
  var materials=jdbc.query("SELECT m.* FROM material_entity m JOIN cytology_preparation p ON p.id=m.cytology_preparation_id JOIN cytology_specimen s ON s.id=p.specimen_id WHERE s.container_id=? ORDER BY m.created_at,m.id LIMIT 100",MaterialQueries.MAPPER,container);
  var events=s==null?List.<Event>of():jdbc.query("SELECT * FROM cytology_event WHERE specimen_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),r.getObject("preparation_id",UUID.class),r.getString("action"),r.getInt("transferred"),r.getInt("consumed"),r.getInt("discarded"),r.getInt("returned"),r.getInt("slides"),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("recorded_at",OffsetDateTime.class).toInstant()),s.id(),(page-1)*20);
  audit.append(c.hospital(),"CYTOLOGY_READ_V1","CYTOLOGY_CONTAINER",container,null,s==null?0:s.version());return new Detail(rid,container,c.patient(),c.caseId(),c.number(),s,prep,materials,page,events);
 }
 public IdempotentCommands.Result command(UUID rid,UUID container,Action action,Command input,String key){
  var errors=validator.validate(input);if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);var initial=context(rid,container,action);
  try{return commands.execute(initial.hospital(),"CYTOLOGY_"+action+"_V1",key,Map.of("request",rid,"container",container,"command",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor actor){context(rid,container,action);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid);var c=context(rid,container,action);var s=specimen(container);long old=s==null?-1:s.version();if(old!=input.expectedVersion())throw conflict("VERSION_CONFLICT");if(!container.equals(input.confirmedContainerId()))throw missing();
    UUID sid=s==null?UUID.randomUUID():s.id(),prepId=null;int transfer=0,consumed=0,discarded=0,returned=0,slides=0,remaining=s==null?c.quantity():s.remaining();long qcVersion=s==null?-1:s.qcVersion();String qcState=s==null?"PENDING":s.qcState();
    boolean accountingFailure=action==Action.FAIL;
    if(!accountingFailure&&jdbc.queryForObject("SELECT (SELECT count(*) FROM quality_head WHERE request_id=? AND state='IDENTITY_MISMATCH')+(SELECT count(*) FROM cytology_specimen WHERE request_id=? AND qc_state='IDENTITY_MISMATCH')",Long.class,rid,rid)>0)throw conflict("QC_QUARANTINED");
    if(action==Action.REGISTER){
     if(s!=null||input.metadata().isBlank())throw conflict("CYTOLOGY_STATE");
     if(jdbc.queryForObject("SELECT (SELECT count(*) FROM material_entity WHERE container_id=?)+(SELECT count(*) FROM gross_cassette_source WHERE container_id=?)+(SELECT count(*) FROM frozen_case WHERE container_id=?)",Long.class,container,container,container)>0)throw conflict("CYTOLOGY_LEGACY_SOURCE");
     jdbc.update("INSERT INTO cytology_specimen(id,hospital_id,patient_id,request_id,case_id,container_id,sample_description,unit,initial_quantity,remaining,version,qc_state,qc_version) VALUES(?,?,?,?,?,?,?,'SYN_PORTION',?,?,0,'PENDING',-1)",sid,c.hospital(),c.patient(),rid,c.caseId(),container,input.metadata(),c.quantity(),c.quantity());
    }else{
     if(s==null)throw conflict("CYTOLOGY_STATE");if(s.qcState().equals("IDENTITY_MISMATCH")&&!accountingFailure)throw conflict("QC_QUARANTINED");
     switch(action){
      case QC_PASS,QC_FAIL,IDENTITY_MISMATCH -> {qcState=action==Action.QC_PASS?"PASS":action==Action.QC_FAIL?"FAIL":"IDENTITY_MISMATCH";qcVersion++;}
      case PREPARE -> {
       if(!s.qcState().equals("PASS")||input.path()==null||input.metadata().isBlank())throw conflict("CYTOLOGY_NOT_READY");
       if(input.repeatOf()!=null&&jdbc.queryForObject("SELECT count(*) FROM cytology_preparation WHERE specimen_id=? AND id=?",Long.class,sid,input.repeatOf())!=1)throw missing();
       if(jdbc.queryForObject("SELECT count(*) FROM cytology_preparation WHERE specimen_id=?",Long.class,sid)>=100)throw conflict("MATERIAL_LIMIT_REACHED");
       try{CytologyPolicy.reserve(remaining,input.transferred());}catch(IllegalArgumentException e){throw conflict(e.getMessage());}
       prepId=UUID.randomUUID();transfer=input.transferred();remaining-=transfer;
       jdbc.update("INSERT INTO cytology_preparation(id,specimen_id,hospital_id,request_id,case_id,path,metadata,transferred,source_qc_version,repeat_of,state,version,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,'RESERVED',0,?)",prepId,sid,c.hospital(),rid,c.caseId(),input.path().name(),input.metadata(),transfer,s.qcVersion(),input.repeatOf(),actor.id());
      }
      case COMPLETE,FAIL -> {
       var values=jdbc.query("SELECT * FROM cytology_preparation WHERE specimen_id=? AND id=?",PREPARATION,sid,input.preparationId());if(values.isEmpty())throw missing();var p=values.getFirst();prepId=p.id();
       if(p.version()!=input.preparationVersion())throw conflict("VERSION_CONFLICT");if(!p.state().equals("RESERVED"))throw conflict("CYTOLOGY_STATE");
       if(!accountingFailure&&(!s.qcState().equals("PASS")||s.qcVersion()!=p.sourceQcVersion()))throw conflict("CYTOLOGY_SOURCE_STALE");
       transfer=p.transferred();consumed=input.consumed();discarded=input.discarded();returned=input.returned();slides=input.slides();
       try{CytologyPolicy.reconcile(transfer,consumed,discarded,returned,slides,accountingFailure);}catch(IllegalArgumentException e){throw conflict(e.getMessage());}
       remaining+=returned;
       int outputs=accountingFailure?0:slides+(p.path()==Path.CELL_BLOCK?1:0);
       if(jdbc.queryForObject("SELECT count(*) FROM material_entity WHERE request_id=?",Long.class,rid)+outputs>100)throw conflict("MATERIAL_LIMIT_REACHED");
       if(jdbc.update("UPDATE cytology_preparation SET state=?,version=version+1 WHERE id=? AND version=? AND state='RESERVED'",accountingFailure?"FAILED":"COMPLETED",prepId,p.version())!=1)throw conflict("VERSION_CONFLICT");
       if(!accountingFailure){UUID block=p.path()==Path.CELL_BLOCK?material(c,rid,container,prepId,"BLOCK",null,actor.id(),input.reason()):null;for(int i=0;i<slides;i++)material(c,rid,container,prepId,"SLIDE",block,actor.id(),input.reason());}
      }
      default -> throw conflict("CYTOLOGY_STATE");
     }
     if(jdbc.update("UPDATE cytology_specimen SET remaining=?,qc_state=?,qc_version=?,version=version+1 WHERE id=? AND version=?",remaining,qcState,qcVersion,sid,old)!=1)throw conflict("VERSION_CONFLICT");
    }
    jdbc.update("INSERT INTO cytology_event(specimen_id,version,preparation_id,action,transferred,consumed,discarded,returned,slides,reason,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",sid,old+1,prepId,action.name(),transfer,consumed,discarded,returned,slides,input.reason(),actor.id());
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"CYTOLOGY_CONTAINER",container,old+1),old<0?null:old);
   }
  });}catch(ApiException e){rejections.record(initial.hospital(),container,rid,action.name(),e.code());throw e;}
 }
 private UUID material(Context c,UUID rid,UUID container,UUID prep,String kind,UUID block,UUID actor,String reason){
  UUID id=UUID.randomUUID();String barcode=LabelBarcode.create(id);jdbc.update("INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,container_id,block_id,cytology_preparation_id,created_by) VALUES(?,?,?,?,?,?,?,'ORIGINAL',?,?,?,?,?,?)",id,c.hospital(),c.patient(),rid,c.caseId(),kind,kind.equals("BLOCK")?"CYTOLOGY_BLOCK":"CYTOLOGY_SLIDE",(kind.equals("BLOCK")?"DEV-B-":"DEV-S-")+id,barcode,container,block,prep,actor);
  labels.registerMaterialIdentity(id,barcode);jdbc.update("INSERT INTO material_event(material_id,material_version,action,related_id,reason,actor_id) VALUES(?,0,'CREATE',?,?,?)",id,block,reason,actor);return id;
 }
 private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"CYTOLOGY_NOT_FOUND","Cytology resource unavailable");}
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Cytology identity, quantity or version requires review");}
}
