package com.pis.archive;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.specimen.ReceptionService;
import com.pis.material.MaterialService;
import com.pis.report.OutputService;
import com.pis.api.ApiException;
import com.pis.audit.AuditRecorder;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.*;
import jakarta.validation.Validator;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.pis.archive.ArchiveContracts.*;
@Service
public class ArchiveService {
 private final JdbcTemplate jdbc; private final RequestService requests; private final ReceptionService reception; private final WorkflowAccess access; private final MaterialService materials; private final OutputService outputs; private final IdempotentCommands commands; private final AuditRecorder audit; private final Validator validator; private final JsonMapper json; private final int maxDays; private final ArchiveRejectionAudit rejections;
 public ArchiveService(JdbcTemplate jdbc,RequestService requests,ReceptionService reception,WorkflowAccess access,MaterialService materials,OutputService outputs,IdempotentCommands commands,AuditRecorder audit,Validator validator,JsonMapper json,ArchiveRejectionAudit rejections,@Value("${pis.archive.max-loan-days:30}") int maxDays) {
  if(maxDays<1||maxDays>365)throw new IllegalArgumentException("Synthetic loan bound must be 1..365 days");this.jdbc=jdbc;this.requests=requests;this.reception=reception;this.access=access;this.materials=materials;this.outputs=outputs;this.commands=commands;this.audit=audit;this.validator=validator;this.json=json;this.maxDays=maxDays;this.rejections=rejections;
 }
 private record Rights(boolean request,boolean approve,boolean manage) { }
 private record Context(UUID hospital,UUID scope,UUID caseId,UUID patient,String number,Rights rights) { }
 private Rights rights(UUID scope,UUID user) {
  if(TransactionSynchronizationManager.isActualTransactionActive()) {
   jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",user);
   jdbc.queryForList("SELECT user_id FROM workflow_grant WHERE scope_id=? AND user_id=? FOR SHARE",scope,user);
   jdbc.queryForList("SELECT user_id FROM archive_grant WHERE scope_id=? AND user_id=? FOR SHARE",scope,user);
  }
  var r=jdbc.query("SELECT a.can_request,a.can_approve,a.can_manage FROM archive_grant a JOIN workflow_grant g USING(user_id,scope_id) JOIN app_user u ON u.id=a.user_id WHERE a.scope_id=? AND a.user_id=? AND a.revoked_at IS NULL AND a.valid_until>statement_timestamp() AND g.can_read AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp()) AND u.enabled AND u.synthetic_only",(v,i)->new Rights(v.getBoolean(1),v.getBoolean(2),v.getBoolean(3)),scope,user);
  if(r.isEmpty())throw missing();return r.getFirst();
 }
 private Context context(UUID rid,Action action) {
  try { var q=requests.detail(rid);var s=access.require(q.scopeId(),WorkflowAccess.Permission.READ);var r=rights(q.scopeId(),access.actor().id());
   if(action!=null&&!switch(action){case LOAN,CANCEL->r.request();case APPROVE,REJECT->r.approve();default->r.manage();})throw missing();
   var received=reception.receivedSource(rid);return new Context(s.hospitalId(),q.scopeId(),received.caseId(),q.patientId(),received.caseNumber(),r);
  } catch(org.springframework.security.access.AccessDeniedException e){throw missing();}
 }
 private List<Source> sources(UUID rid,Context c) {
  var values=new ArrayList<Source>();for(var m:materials.archiveSources(rid))values.add(new Source(m.id(),m.kind(),m.barcode(),m.version(),m.status(),null,null));
  for(var a:outputs.archiveArtifacts(rid,c.scope()))values.add(new Source(a.id(),"REPORT","SYN-PDF-"+a.id(),a.version(),"IMMUTABLE_ARTIFACT",a.revisionId(),a.sha256()));return List.copyOf(values);
 }
 private long version(UUID rid) {var rows=jdbc.queryForList("SELECT version FROM archive_book WHERE request_id=?",Long.class,rid);return rows.isEmpty()?-1:rows.getFirst();}
 private List<Item> items(UUID rid,List<Source> sources) {
  var status=new HashMap<UUID,String>();sources.forEach(s->status.put(s.id(),s.status()));
  return jdbc.query("SELECT *,coalesce(material_id,artifact_id) AS source_id FROM archive_item WHERE request_id=? ORDER BY id LIMIT 100",(r,i)->new Item(r.getObject("id",UUID.class),r.getString("kind"),r.getObject("source_id",UUID.class),r.getLong("source_version"),r.getString("barcode"),r.getObject("revision_id",UUID.class),r.getString("artifact_hash"),r.getLong("version"),r.getObject("location_id",UUID.class),r.getString("condition"),r.getString("policy_label"),r.getBoolean("legal_hold"),status.getOrDefault(r.getObject("source_id",UUID.class),"UNAVAILABLE")),rid);
 }
 private List<Loan> loans(UUID rid) {return jdbc.query("SELECT * FROM archive_loan WHERE request_id=? ORDER BY created_at DESC,id LIMIT 100",(r,i)->new Loan(r.getObject("id",UUID.class),r.getObject("applicant_id",UUID.class),r.getObject("borrower_id",UUID.class),r.getObject("approver_id",UUID.class),r.getString("purpose"),r.getObject("due_at",OffsetDateTime.class).toInstant(),r.getString("state")),rid);}
 private List<LoanItem> loanItems(UUID rid) {return jdbc.query("SELECT * FROM archive_loan_item WHERE request_id=? ORDER BY loan_id,item_id LIMIT 2000",(r,i)->new LoanItem(r.getObject("loan_id",UUID.class),r.getObject("item_id",UUID.class),r.getString("state")),rid);}
 private List<Snapshot> snapshot(UUID rid,UUID id) {if(id==null)return List.of();if(jdbc.queryForObject("SELECT count(*) FROM archive_inventory WHERE request_id=? AND id=?",Long.class,rid,id)!=1)throw missing();return jdbc.query("SELECT * FROM archive_inventory_item WHERE request_id=? AND inventory_id=? ORDER BY item_id LIMIT 100",(r,i)->new Snapshot(r.getObject("item_id",UUID.class),r.getLong("item_version"),r.getObject("location_id",UUID.class),r.getString("condition"),r.getString("loan_state")),rid,id);}
 @Transactional(timeout=10) public View view(UUID rid,UUID inventory,int page) {
  context(rid,null);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);var c=context(rid,null);if(page<1||page>10000)throw invalid();
  var s=sources(rid,c);var v=version(rid);audit.append(c.hospital(),"ARCHIVE_READ_V1","ARCHIVE_BOOK",rid,null,Math.max(0,v));
  return new View(rid,c.caseId(),c.patient(),c.number(),v,access.actor().id(),c.rights().request(),c.rights().approve(),c.rights().manage(),maxDays,s,items(rid,s),jdbc.query("SELECT id,code FROM archive_location WHERE scope_id=? ORDER BY code LIMIT 100",(r,i)->new Location(r.getObject(1,UUID.class),r.getString(2)),c.scope()),loans(rid),loanItems(rid),jdbc.query("SELECT * FROM archive_inventory WHERE request_id=? ORDER BY recorded_at DESC,id LIMIT 100",(r,i)->new Inventory(r.getObject("id",UUID.class),r.getLong("book_version"),r.getObject("recorded_at",OffsetDateTime.class).toInstant()),rid),inventory,snapshot(rid,inventory),jdbc.query("SELECT * FROM archive_event WHERE request_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),Action.valueOf(r.getString("action")),r.getObject("item_id",UUID.class),r.getObject("loan_id",UUID.class),r.getObject("inventory_id",UUID.class),r.getObject("reference_id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("reason"),r.getString("detail"),r.getObject("recorded_at",OffsetDateTime.class).toInstant(),r.getString("physical_confirmation")),rid,(page-1)*20),page);
 }
 private void location(Context c,UUID target,UUID own) {
  if(target==null)return;
  if(jdbc.queryForList("SELECT id FROM archive_location WHERE scope_id=? AND id=? FOR UPDATE",UUID.class,c.scope(),target).isEmpty())throw missing();
  if(jdbc.queryForObject("SELECT count(*) FROM archive_item WHERE location_id=? AND (?::uuid IS NULL OR id<>?)",Long.class,target,own,own)>0)throw conflict("ARCHIVE_LOCATION_OCCUPIED");
 }
 private void update(Item item,UUID loc,String condition) {if(jdbc.update("UPDATE archive_item SET location_id=?,condition=?,version=version+1 WHERE id=? AND version=?",loc,condition,item.id(),item.version())!=1)throw conflict("VERSION_CONFLICT");}
 private String occupancy(UUID item) {var a=jdbc.queryForList("SELECT state FROM archive_loan_item WHERE item_id=? AND state IN ('RESERVED','OUT')",String.class,item);return a.isEmpty()?null:a.getFirst();}
 private void noOccupancy(Item item) {if(occupancy(item.id())!=null)throw conflict("ARCHIVE_ITEM_OCCUPIED");}
 public IdempotentCommands.Result command(UUID rid,Action action,Command input,String key) {
  var errors=validator.validate(input);if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);var initial=context(rid,action);
  try { return commands.execute(initial.hospital(),"ARCHIVE_"+action+"_V1",key,Map.of("request",rid,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor){context(rid,action);}
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);context(rid,action);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid);var c=context(rid,action);
    if(!rid.equals(input.confirmedRequestId()))throw conflict("ARCHIVE_IDENTITY_MISMATCH");long old=version(rid);if(old!=input.expectedVersion())throw conflict("VERSION_CONFLICT");
    if(old<0)jdbc.update("INSERT INTO archive_book(request_id,hospital_id,scope_id,case_id,patient_id) VALUES(?,?,?,?,?)",rid,c.hospital(),c.scope(),c.caseId(),c.patient());
    var available=sources(rid,c);var all=items(rid,available);UUID itemId=null,loanId=null,inventoryId=null;Map<String,Object> detail=new LinkedHashMap<>();
    if(action==Action.LOCATION) {
     if(input.code()==null)throw invalid();if(jdbc.queryForObject("SELECT count(*) FROM archive_location WHERE scope_id=?",Long.class,c.scope())>=100)throw conflict("ARCHIVE_LIMIT");
     jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?::text,26))",c.scope().toString());if(jdbc.queryForObject("SELECT count(*) FROM archive_location WHERE scope_id=?",Long.class,c.scope())>=100)throw conflict("ARCHIVE_LIMIT");if(jdbc.queryForObject("SELECT count(*) FROM archive_location WHERE scope_id=? AND code=?",Long.class,c.scope(),input.code())>0)throw conflict("ARCHIVE_LOCATION_OCCUPIED");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO archive_location(id,scope_id,code,created_by) VALUES(?,?,?,?)",id,c.scope(),input.code(),actor.id());detail.put("locationId",id);detail.put("code",input.code());
    } else if(action==Action.REGISTER) {
     if(all.size()>=100)throw conflict("ARCHIVE_LIMIT");var source=available.stream().filter(s->s.id().equals(input.sourceId())).findFirst().orElseThrow(ArchiveService::missing);
     if(!Objects.equals(input.sourceVersion(),source.version()))throw conflict("VERSION_CONFLICT");if(!source.barcode().equals(input.barcode()))throw conflict("ARCHIVE_BARCODE_MISMATCH");if(input.policyLabel()==null||input.legalHold()==null)throw invalid();if(all.stream().anyMatch(i->i.sourceId().equals(source.id())))throw conflict("ARCHIVE_ALREADY_REGISTERED");location(c,input.locationId(),null);itemId=UUID.randomUUID();
     jdbc.update("INSERT INTO archive_item(id,request_id,case_id,scope_id,material_id,artifact_id,kind,barcode,source_version,artifact_hash,revision_id,location_id,policy_label,legal_hold) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",itemId,rid,c.caseId(),c.scope(),source.kind().equals("REPORT")?null:source.id(),source.kind().equals("REPORT")?source.id():null,source.kind(),source.barcode(),source.version(),source.hash(),source.revisionId(),input.locationId(),input.policyLabel(),input.legalHold());detail.put("source",source);detail.put("locationId",input.locationId()==null?"":input.locationId());
    } else if(action==Action.LOAN) {
     if(loans(rid).size()>=100)throw conflict("ARCHIVE_LIMIT");if(input.borrowerId()==null||!rights(c.scope(),input.borrowerId()).request()||input.purpose()==null||input.purpose().isBlank()||!ArchivePolicy.validDue(input.dueAt(),Instant.now(),maxDays)||input.items().isEmpty()||input.items().stream().map(Selection::id).distinct().count()!=input.items().size())throw invalid();
     loanId=UUID.randomUUID();jdbc.update("INSERT INTO archive_loan(id,request_id,applicant_id,borrower_id,purpose,due_at,state) VALUES(?,?,?,?,?,?,'REQUESTED')",loanId,rid,actor.id(),input.borrowerId(),input.purpose(),OffsetDateTime.ofInstant(input.dueAt(),ZoneOffset.UTC));
     for(var choice:input.items()){var item=all.stream().filter(i->i.id().equals(choice.id())).findFirst().orElseThrow(ArchiveService::missing);if(item.version()!=choice.version())throw conflict("VERSION_CONFLICT");noOccupancy(item);if(!ArchivePolicy.lendable(item.condition(),item.currentSourceStatus()))throw conflict("ARCHIVE_SOURCE_UNSUITABLE");jdbc.update("INSERT INTO archive_loan_item(request_id,loan_id,item_id,state) VALUES(?,?,?,'RESERVED')",rid,loanId,item.id());update(item,item.locationId(),item.condition());}detail.put("items",input.items());detail.put("purpose",input.purpose());detail.put("borrower",input.borrowerId());detail.put("dueAt",input.dueAt());
    } else if(List.of(Action.APPROVE,Action.REJECT,Action.CANCEL).contains(action)) {
     var loan=loans(rid).stream().filter(l->l.id().equals(input.loanId())).findFirst().orElseThrow(ArchiveService::missing);loanId=loan.id();if(!List.of("REQUESTED","APPROVED").contains(loan.state()))throw conflict("ARCHIVE_STATE");
     if(action==Action.CANCEL&&!loan.applicantId().equals(actor.id()))throw missing();if(action!=Action.CANCEL&&loan.applicantId().equals(actor.id()))throw conflict("ARCHIVE_SEPARATION");
     if(action==Action.APPROVE){if(!loan.state().equals("REQUESTED")||!loan.dueAt().isAfter(Instant.now())||!rights(c.scope(),loan.borrowerId()).request())throw conflict("ARCHIVE_STATE");jdbc.update("UPDATE archive_loan SET state='APPROVED',approver_id=? WHERE id=?",actor.id(),loanId);}
     else {if(loanItems(rid).stream().anyMatch(l->l.loanId().equals(loan.id())&&l.state().equals("OUT")))throw conflict("ARCHIVE_OUTSTANDING");for(var li:loanItems(rid))if(li.loanId().equals(loan.id())&&li.state().equals("RESERVED")){var item=all.stream().filter(i->i.id().equals(li.itemId())).findFirst().orElseThrow();update(item,item.locationId(),item.condition());}jdbc.update("UPDATE archive_loan_item SET state='RELEASED' WHERE loan_id=? AND state='RESERVED'",loanId);jdbc.update("UPDATE archive_loan SET state=? WHERE id=?",action==Action.CANCEL?"CANCELLED":"REJECTED",loanId);}
    } else if(action==Action.INVENTORY) {
     if(all.isEmpty()||jdbc.queryForObject("SELECT count(*) FROM archive_inventory WHERE request_id=?",Long.class,rid)>=100)throw conflict("ARCHIVE_LIMIT");inventoryId=UUID.randomUUID();jdbc.update("INSERT INTO archive_inventory(id,request_id,book_version,created_by) VALUES(?,?,?,?)",inventoryId,rid,old+1,actor.id());
     jdbc.update("INSERT INTO archive_inventory_item(inventory_id,request_id,item_id,item_version,location_id,condition,loan_state) SELECT ?,i.request_id,i.id,i.version,i.location_id,i.condition,l.state FROM archive_item i LEFT JOIN archive_loan_item l ON l.item_id=i.id AND l.state IN ('RESERVED','OUT') WHERE i.request_id=?",inventoryId,rid);
    } else {
     var item=all.stream().filter(i->i.id().equals(input.itemId())).findFirst().orElseThrow(ArchiveService::missing);itemId=item.id();if(!Objects.equals(input.itemVersion(),item.version()))throw conflict("VERSION_CONFLICT");if(!item.barcode().equals(input.barcode()))throw conflict("ARCHIVE_BARCODE_MISMATCH");detail.put("before",item);detail.put("recordedOnly",true);
     if(action==Action.CHECK) {
      inventoryId=input.inventoryId();var snap=snapshot(rid,inventoryId).stream().filter(s->s.itemId().equals(item.id())).findFirst().orElseThrow(ArchiveService::missing);if(snap.itemVersion()!=item.version())throw conflict("ARCHIVE_SNAPSHOT_STALE");if(input.observation()==null)throw invalid();if(input.observation().equals("MATCH")&&!Objects.equals(input.locationId(),snap.locationId()))throw conflict("ARCHIVE_IDENTITY_MISMATCH");if(input.locationId()!=null&&jdbc.queryForObject("SELECT count(*) FROM archive_location WHERE scope_id=? AND id=?",Long.class,c.scope(),input.locationId())!=1)throw missing();detail.put("observation",input.observation());detail.put("observedLocation",input.locationId()==null?"":input.locationId());detail.put("snapshotVersion",snap.itemVersion());
     } else if(action==Action.CHECKOUT||action==Action.RETURN) {
      var loan=loans(rid).stream().filter(l->l.id().equals(input.loanId())).findFirst().orElseThrow(ArchiveService::missing);loanId=loan.id();var li=loanItems(rid).stream().filter(l->l.loanId().equals(loan.id())&&l.itemId().equals(item.id())).findFirst().orElseThrow(ArchiveService::missing);
      if(action==Action.CHECKOUT){if(!loan.state().equals("APPROVED")||!li.state().equals("RESERVED")||!loan.dueAt().isAfter(Instant.now()))throw conflict("ARCHIVE_STATE");if(!rights(c.scope(),loan.applicantId()).request()||!rights(c.scope(),loan.borrowerId()).request()||loan.approverId()==null||!rights(c.scope(),loan.approverId()).approve())throw missing();if(!ArchivePolicy.lendable(item.condition(),item.currentSourceStatus()))throw conflict("ARCHIVE_SOURCE_UNSUITABLE");update(item,null,item.condition());jdbc.update("UPDATE archive_loan_item SET state='OUT' WHERE loan_id=? AND item_id=?",loanId,itemId);}
      else {if(!li.state().equals("OUT")||item.condition().equals("LOST"))throw conflict("ARCHIVE_STATE");location(c,input.locationId(),item.id());update(item,input.locationId(),item.condition());jdbc.update("UPDATE archive_loan_item SET state='RETURNED' WHERE loan_id=? AND item_id=?",loanId,itemId);if(jdbc.queryForObject("SELECT count(*) FROM archive_loan_item WHERE loan_id=? AND state IN ('OUT','RESERVED')",Long.class,loanId)==0)jdbc.update("UPDATE archive_loan SET state='CLOSED' WHERE id=?",loanId);}
     } else if(List.of(Action.DAMAGE,Action.LOST,Action.FOUND).contains(action)) {
      String condition=ArchivePolicy.condition(item.condition(),action.name());if(condition==null)throw conflict("ARCHIVE_STATE");update(item,action==Action.LOST?null:item.locationId(),condition);
     } else {
      noOccupancy(item);if(action==Action.CORRECT){if(input.referenceId()==null||jdbc.queryForObject("SELECT count(*) FROM archive_event WHERE request_id=? AND id=? AND item_id=? AND action='CHECK' AND detail->>'observation'='DIFFERENCE' AND (detail->>'snapshotVersion')::bigint=?",Long.class,rid,input.referenceId(),item.id(),item.version())!=1)throw conflict("ARCHIVE_SNAPSHOT_STALE");if(jdbc.queryForObject("SELECT count(*) FROM archive_event WHERE reference_id=? AND action='CORRECT'",Long.class,input.referenceId())>0)throw conflict("ARCHIVE_STATE");}
      else if(action!=Action.MOVE&&action!=Action.REMOVE)throw invalid();
      if(action==Action.MOVE&&input.locationId()==null||item.condition().equals("LOST"))throw conflict("ARCHIVE_STATE");UUID loc=action==Action.REMOVE?null:input.locationId();if(action==Action.REMOVE&&item.locationId()==null)throw conflict("ARCHIVE_STATE");location(c,loc,item.id());update(item,loc,item.condition());
     }
     detail.put("after",jdbc.queryForMap("SELECT version,location_id,condition FROM archive_item WHERE request_id=? AND id=?",rid,item.id()));
    }
    context(rid,action); // Recheck time-limited actor rights immediately before the atomic event/head commit.
    UUID event=UUID.randomUUID();jdbc.update("INSERT INTO archive_event(id,request_id,version,action,item_id,loan_id,inventory_id,reference_id,actor_id,reason,detail) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb)",event,rid,old+1,action.name(),itemId,loanId,inventoryId,action==Action.CORRECT?input.referenceId():null,actor.id(),input.reason(),json.writeValueAsString(detail));
    if(jdbc.update("UPDATE archive_book SET version=version+1 WHERE request_id=? AND version=?",rid,old)!=1)throw conflict("VERSION_CONFLICT");return new IdempotentCommands.Mutation(new CommandReceipt(200,"ARCHIVE_BOOK",rid,old+1),old<0?null:old);
   }
  }); } catch(ApiException e){rejections.record(initial.hospital(),rid,action.name(),e.code());throw e;}
 }
 public List<BatchResult> batch(UUID rid,Batch input) {
  var errors=validator.validate(input);if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);context(rid,null);if(input.entries().stream().map(BatchEntry::key).distinct().count()!=input.entries().size())throw invalid();var results=new ArrayList<BatchResult>();boolean stop=false;
  for(int i=0;i<input.entries().size();i++){var e=input.entries().get(i);if(stop){results.add(new BatchResult(i,"NOT_ATTEMPTED","PREVIOUS_RESULT_UNKNOWN",null));continue;}try{results.add(new BatchResult(i,"SUCCESS",null,command(rid,e.action(),e.command(),e.key())));}catch(ApiException error){results.add(new BatchResult(i,"FAILED",error.code(),null));if(error.status().value()>=500)stop=true;}catch(RuntimeException error){results.add(new BatchResult(i,"UNKNOWN","RESULT_UNKNOWN",null));stop=true;}}return List.copyOf(results);
 }
 private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"ARCHIVE_NOT_FOUND","Archive unavailable");}
 private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"ARCHIVE_INPUT_INVALID","Explicit synthetic custody input required");}
 private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"Archive requires current identity, scope and state");}
}
