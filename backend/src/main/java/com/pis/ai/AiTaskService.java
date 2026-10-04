package com.pis.ai;

import com.pis.accession.*;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.idempotency.*;
import com.pis.scan.ScanFormat;
import com.pis.storage.*;
import jakarta.validation.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.pis.ai.AiTaskContracts.*;

/** Authenticated, explicit local contract work. No model executor, scheduler or clinical permission. */
@Service
public class AiTaskService {
 public static final String SCHEMA="SYN-CONTRACT-WORKER-1";
 private final JdbcTemplate jdbc;private final WorkflowAccess access;private final RequestService requests;private final AiRegistryService registry;private final StorageService storage;private final IdempotentCommands commands;private final AuditRecorder audit;private final Validator validator;private final SyntheticWorkerMode mode;private final TransactionTemplate tx;private final Clock clock;private final JsonMapper json=JsonMapper.builder().build();
 @Autowired public AiTaskService(JdbcTemplate jdbc,WorkflowAccess access,RequestService requests,AiRegistryService registry,StorageService storage,IdempotentCommands commands,AuditRecorder audit,Validator validator,SyntheticWorkerMode mode,PlatformTransactionManager manager){this(jdbc,access,requests,registry,storage,commands,audit,validator,mode,manager,Clock.systemUTC());}
 public AiTaskService(JdbcTemplate jdbc,WorkflowAccess access,RequestService requests,AiRegistryService registry,StorageService storage,IdempotentCommands commands,AuditRecorder audit,Validator validator,SyntheticWorkerMode mode,PlatformTransactionManager manager,Clock clock){this.jdbc=jdbc;this.access=access;this.requests=requests;this.registry=registry;this.storage=storage;this.commands=commands;this.audit=audit;this.validator=validator;this.mode=mode;this.clock=clock;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
 private void valid(Object o){var errors=validator.validate(o);if(!errors.isEmpty())throw new ConstraintViolationException(errors);}
 private static ApiException error(HttpStatus status,String code){return new ApiException(status,code,"Synthetic technical task unavailable; refresh current authorization and exact versions; never a diagnostic result");}
 private static ApiException conflict(){return error(HttpStatus.CONFLICT,"AI_TASK_CONFLICT");}
 private static ApiException missing(){return error(HttpStatus.NOT_FOUND,"AI_TASK_NOT_FOUND");}
 private static Timestamp stamp(Instant t){return t==null?null:Timestamp.from(t);}
 private static Instant instant(java.sql.ResultSet r,String name)throws java.sql.SQLException{var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
 private UUID authorize(UUID request,boolean work){mode.require();var scope=requests.authorizedScope(request);var a=access.actor();
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForList("SELECT user_id FROM ai_task_grant WHERE scope_id=? AND user_id=? FOR SHARE",scope.id(),a.id());
  a=access.actor();if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_task_grant WHERE scope_id=? AND user_id=? AND qualification=? AND can_submit AND (NOT ? OR can_work) AND valid_until>statement_timestamp() AND revoked_at IS NULL)",Boolean.class,scope.id(),a.id(),SCHEMA,work)))throw missing();return scope.hospitalId();
 }
 private org.springframework.jdbc.core.RowMapper<Job> mapper(){return (r,i)->new Job(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("scan_id",UUID.class),r.getObject("assessment_id",UUID.class),r.getObject("scope_id",UUID.class),r.getObject("hospital_id",UUID.class),r.getObject("owner_id",UUID.class),r.getLong("owner_auth_version"),json.readValue(r.getString("binding"),AiRegistryService.WorkerBinding.class),r.getString("binding_hash"),r.getString("worker_schema"),r.getString("state"),r.getLong("version"),r.getInt("generation"),(Integer)r.getObject("progress"),r.getObject("lease_id",UUID.class),r.getObject("lease_actor",UUID.class),instant(r,"lease_until"),instant(r,"deadline"),instant(r,"retry_at"),r.getString("error_code"),r.getObject("artifact_id",UUID.class));}
 private Job row(UUID request,UUID id,boolean lock){var list=jdbc.query("SELECT * FROM ai_task WHERE request_id=? AND id=?"+(lock?" FOR UPDATE":""),mapper(),request,id);if(list.isEmpty())throw missing();return list.getFirst();}
 private Job job(UUID request,UUID id,boolean work,boolean lock){authorize(request,work);var j=row(request,id,lock);var a=access.actor();if(!j.ownerId().equals(a.id())||j.ownerAuthVersion()!=a.authVersion())throw missing();registry.authorizeTaskHistory(request,j.scanId());return j;}
 private void current(Job j){if(!j.bindingHash().equals(ScanFormat.sha(json.writeValueAsString(j.binding()).getBytes(StandardCharsets.UTF_8))))throw conflict();if(!j.binding().equals(registry.workerBinding(j.requestId(),j.scanId(),j.assessmentId())))throw conflict();}
 // Never catch a participating transaction's exception and attempt to commit its rollback-only parent.
 private String invalid(Job j){if(!j.bindingHash().equals(ScanFormat.sha(json.writeValueAsString(j.binding()).getBytes(StandardCharsets.UTF_8))))return "INPUT_DEPENDENCY_CHANGED";return registry.workerBindingCurrent(j.requestId(),j.scanId(),j.assessmentId(),j.binding())?null:"INPUT_DEPENDENCY_CHANGED";}
 private void event(Job j,String action,String reason){jdbc.update("INSERT INTO ai_task_event(task_id,version,generation,state,action,error_code,actor_id,reason) VALUES(?,?,?,?,?,?,?,?)",j.id(),j.version(),j.generation(),j.state(),action,j.errorCode(),access.actor().id(),reason);}
 private static IdempotentCommands.Mutation receipt(Job j,Long previous){return new IdempotentCommands.Mutation(new CommandReceipt(200,"SYNTHETIC_AI_TASK",j.id(),j.version()),previous);}
 public IdempotentCommands.Result submit(UUID request,UUID scan,Submit input,String key){valid(input);var hospital=authorize(request,false);return commands.execute(hospital,"AI_TASK_SUBMIT_V1",key,Map.of("request",request,"scan",scan,"input",input),new IdempotentCommands.Work(){
  public void authorize(CurrentActor.Actor a){AiTaskService.this.authorize(request,false);registry.workerBinding(request,scan,input.assessmentId());}
  public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){var j=job(request,r.resourceId(),false,true);current(j);}
  public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){var binding=registry.workerBinding(request,scan,input.assessmentId());UUID id=UUID.randomUUID();String state=SCHEMA.equals(input.workerSchema())?"QUEUED":"UNSUPPORTED";String encoded=json.writeValueAsString(binding);jdbc.update("INSERT INTO ai_task(id,request_id,scope_id,hospital_id,scan_id,assessment_id,owner_id,owner_auth_version,binding,binding_hash,worker_schema,reason,state) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",id,request,binding.decision().scopeId(),hospital,scan,input.assessmentId(),a.id(),a.authVersion(),encoded,ScanFormat.sha(encoded.getBytes(StandardCharsets.UTF_8)),input.workerSchema(),input.reason(),state);jdbc.update("INSERT INTO ai_task_outbox(task_id,state) VALUES(?,?)",id,state.equals("QUEUED")?"PENDING":"DONE");var j=row(request,id,false);event(j,"SUBMIT",input.reason());return receipt(j,null);}
 });}
 public View detail(UUID request,UUID id){return tx.execute(t->{var j=job(request,id,false,false);String why=invalid(j);audit.append(j.hospitalId(),"AI_TASK_READ_V1","SYNTHETIC_AI_TASK",id,null,j.version());return new View(j,why==null?j.state():"INVALIDATED",why,false,jdbc.queryForList("SELECT version,generation,state,action,error_code,actor_id,occurred_at FROM ai_task_event WHERE task_id=? ORDER BY version DESC LIMIT 100",id));});}
 public Listing list(UUID request,UUID scan,int page){if(page<1||page>5)throw error(HttpStatus.BAD_REQUEST,"AI_TASK_PAGE");return tx.execute(t->{var hospital=authorize(request,false);registry.authorizeTaskHistory(request,scan);var a=access.actor();var jobs=jdbc.query("SELECT * FROM ai_task WHERE request_id=? AND scan_id=? AND owner_id=? AND owner_auth_version=? ORDER BY created_at DESC,id LIMIT 20 OFFSET ?",mapper(),request,scan,a.id(),a.authVersion(),(page-1)*20);audit.append(hospital,"AI_TASK_LIST_V1","SCAN_IMPORT",scan,null,0);return new Listing(request,scan,page,jobs,"SYNTHETIC_CONTRACT_ONLY_NO_CLINICAL_EXECUTION");});}
 private void lease(Job j,Command c){if(!j.state().equals("RUNNING")||!Objects.equals(c.generation(),j.generation())||!Objects.equals(c.leaseId(),j.leaseId())||!access.actor().id().equals(j.leaseActor())||!clock.instant().isBefore(j.leaseUntil())||!clock.instant().isBefore(j.deadline()))throw conflict();}
 public IdempotentCommands.Result command(UUID request,UUID id,String action,Command input,String key){valid(input);if(!Set.of("CLAIM","HEARTBEAT","CANCEL","RETRY","RECONCILE").contains(action))throw error(HttpStatus.BAD_REQUEST,"AI_TASK_ACTION");boolean work=Set.of("CLAIM","HEARTBEAT").contains(action);var hospital=authorize(request,work);return commands.execute(hospital,"AI_TASK_"+action+"_V1",key,Map.of("request",request,"id",id,"input",input),new IdempotentCommands.Work(){
  public void authorize(CurrentActor.Actor a){job(request,id,work,false);}
  public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){var j=job(request,id,work,true);if(!Set.of("CANCEL","RECONCILE").contains(action))current(j);}
  public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){var j=job(request,id,work,true);if(j.version()!=input.expectedVersion())throw conflict();Instant now=clock.instant();String state=j.state(),code="";int gen=j.generation();Integer progress=j.progress();UUID leaseId=j.leaseId(),leaseActor=j.leaseActor();Instant until=j.leaseUntil(),deadline=j.deadline(),retry=j.retryAt();
   switch(action){
    case "CLAIM" -> {current(j);if(!state.equals("QUEUED")||gen>=3||retry!=null&&now.isBefore(retry))throw conflict();jdbc.queryForList("SELECT pg_advisory_xact_lock(354401)");if(jdbc.queryForObject("SELECT count(*) FROM ai_task WHERE state='RUNNING' AND lease_until>?",Integer.class,stamp(now))>=2)throw error(HttpStatus.CONFLICT,"AI_WORKER_CAPACITY");if(jdbc.queryForObject("SELECT count(*) FROM ai_task_outbox WHERE task_id=? AND state='PENDING' AND generation=?",Integer.class,id,gen)!=1)throw conflict();state="RUNNING";gen++;progress=0;leaseId=UUID.randomUUID();leaseActor=a.id();until=now.plusSeconds(30);deadline=now.plusSeconds(120);jdbc.update("INSERT INTO ai_task_attempt(task_id,generation,lease_id,actor_id) VALUES(?,?,?,?)",id,gen,leaseId,a.id());}
    case "HEARTBEAT" -> {current(j);lease(j,input);until=now.plusSeconds(30).isBefore(deadline)?now.plusSeconds(30):deadline;}
    case "CANCEL" -> {if(!Set.of("QUEUED","RUNNING","FAILED","TIMEOUT").contains(state))throw conflict();state="CANCELLED";}
    case "RETRY" -> {current(j);if(!Set.of("FAILED","TIMEOUT").contains(state)||gen>=3||retry==null||now.isBefore(retry))throw conflict();state="QUEUED";progress=null;}
    case "RECONCILE" -> {if(Set.of("CANCELLED","INVALIDATED","UNSUPPORTED").contains(state))throw conflict();String why=invalid(j);if(why!=null){state="INVALIDATED";code=why;}else if(state.equals("RUNNING")&&jdbc.queryForObject("SELECT count(*) FROM ai_task_attempt a JOIN storage_version v ON v.id=a.artifact_id WHERE a.task_id=? AND a.generation=? AND v.state='FAILED'",Integer.class,id,gen)>0){state="FAILED";code="ARTIFACT_FAILED";retry=now.plusSeconds(5L<<(Math.max(0,gen-1)));}else if(state.equals("RUNNING")&&(!now.isBefore(until)||!now.isBefore(deadline))){state="TIMEOUT";code="LEASE_EXPIRED";retry=now.plusSeconds(5L<<(Math.max(0,gen-1)));}else throw conflict();}
    default -> throw conflict();
   }
   if(jdbc.update("UPDATE ai_task SET state=?,version=version+1,generation=?,progress=?,lease_id=?,lease_actor=?,lease_until=?,deadline=?,retry_at=?,error_code=? WHERE id=? AND version=?",state,gen,progress,leaseId,leaseActor,stamp(until),stamp(deadline),stamp(retry),code,id,j.version())!=1)throw conflict();if(jdbc.update("UPDATE ai_task_outbox SET state=?,generation=?,updated_at=statement_timestamp() WHERE task_id=?",state.equals("QUEUED")?"PENDING":state.equals("RUNNING")?"LEASED":"DONE",gen,id)!=1)throw conflict();var next=row(request,id,false);event(next,action,input.reason());return receipt(next,j.version());}
 });}
 public byte[] fixture(Job j){return ("PIS-SYNTHETIC-STORAGE-V1\nNON_DIAGNOSTIC_SYNTHETIC_CONTRACT_ONLY\n"+SCHEMA+"\n"+j.id()+"\n"+j.generation()+"\n"+j.bindingHash()+"\n").getBytes(StandardCharsets.UTF_8);}
 private Job runnable(UUID request,UUID id,Command input){return tx.execute(t->{var j=job(request,id,true,true);current(j);if(j.version()!=input.expectedVersion())throw conflict();lease(j,input);return j;});}
 /** Each file phase commits separately; the durable attempt and fixed reserve key resume a crash. */
 public IdempotentCommands.Result run(UUID request,UUID id,Command input){valid(input);
  var saved=tx.execute(t->{var x=job(request,id,true,true);current(x);return x;});
  if(saved.state().equals("SYNTHETIC_SUCCEEDED"))return callback(request,id,new Callback(input.expectedVersion(),input.generation(),input.leaseId(),input.leaseId(),SCHEMA,SCHEMA,saved.artifactId(),ScanFormat.sha(fixture(saved))));
  var j=runnable(request,id,input);byte[] bytes=fixture(j);String hash=ScanFormat.sha(bytes);String key="synthetic-task:"+id+":"+j.generation();
  var reserved=storage.reserveWorkerArtifact(request,new StorageContracts.Reserve(null,-1L,j.binding().caseId(),(long)bytes.length,hash,"SYNTHETIC_WORKER_ARTIFACT","application/octet-stream"),key);
  UUID artifact=reserved.receipt().resourceId();tx.executeWithoutResult(t->{var active=job(request,id,true,true);current(active);lease(active,input);var known=jdbc.queryForList("SELECT artifact_id FROM ai_task_attempt WHERE task_id=? AND generation=? FOR UPDATE",UUID.class,id,j.generation());if(known.isEmpty())throw conflict();if(known.getFirst()==null)jdbc.update("UPDATE ai_task_attempt SET artifact_id=? WHERE task_id=? AND generation=? AND artifact_id IS NULL",artifact,id,j.generation());else if(!artifact.equals(known.getFirst()))throw conflict();});
  var v=storage.detail(request,artifact);if(v.state().equals("RESERVED")){runnable(request,id,input);v=storage.upload(request,artifact,v.version(),bytes.length,new ByteArrayInputStream(bytes));}
  if(Set.of("UPLOADING","FINALIZING").contains(v.state()))v=storage.reconcile(request,artifact);
  if(v.state().equals("STAGED")){runnable(request,id,input);v=storage.finish(request,artifact,new StorageContracts.Command(j.binding().caseId(),v.version()),key+":finish");}
  if(!v.state().equals("READY"))throw error(HttpStatus.CONFLICT,"AI_ARTIFACT_NOT_READY");
  return callback(request,id,new Callback(input.expectedVersion(),j.generation(),j.leaseId(),j.leaseId(),SCHEMA,SCHEMA,artifact,hash));
 }
 public IdempotentCommands.Result callback(UUID request,UUID id,Callback input){valid(input);var hospital=authorize(request,true);if(!SCHEMA.equals(input.source())||!SCHEMA.equals(input.schema())||!input.callbackId().equals(input.leaseId()))throw error(HttpStatus.BAD_REQUEST,"AI_CALLBACK_SCHEMA");return commands.execute(hospital,"AI_TASK_CALLBACK_V1","callback:"+input.callbackId(),Map.of("request",request,"task",id,"input",input),new IdempotentCommands.Work(){
  public void authorize(CurrentActor.Actor a){var j=job(request,id,true,false);current(j);if(!Objects.equals(j.leaseId(),input.leaseId())||j.generation()!=input.generation()||!a.id().equals(j.leaseActor())||!Set.of("RUNNING","SYNTHETIC_SUCCEEDED").contains(j.state()))throw conflict();}
  public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){authorize(a);var j=job(request,id,true,true);current(j);if(!j.state().equals("SYNTHETIC_SUCCEEDED")||!Objects.equals(j.artifactId(),input.artifactId()))throw conflict();}
  public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){var j=job(request,id,true,true);current(j);if(j.version()!=input.expectedVersion())throw conflict();lease(j,new Command(input.expectedVersion(),input.generation(),input.leaseId(),"Validate callback"));
   var attempt=jdbc.queryForList("SELECT artifact_id FROM ai_task_attempt WHERE task_id=? AND generation=? AND lease_id=?",UUID.class,id,j.generation(),j.leaseId());if(attempt.isEmpty()||!input.artifactId().equals(attempt.getFirst()))throw conflict();var v=storage.detail(request,input.artifactId());byte[] expected=fixture(j);if(!v.state().equals("READY")||!v.purpose().equals("SYNTHETIC_WORKER_ARTIFACT")||v.byteSize()!=expected.length||!v.sha256().equals(input.artifactHash())||!v.sha256().equals(ScanFormat.sha(expected)))throw conflict();storage.workerArtifactBytes(request,v.id());
   jdbc.update("INSERT INTO ai_task_callback(task_id,generation,callback_id,payload_hash,result_version) VALUES(?,?,?,?,?)",id,j.generation(),input.callbackId(),ScanFormat.sha(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)),j.version()+1);if(jdbc.update("UPDATE ai_task SET state='SYNTHETIC_SUCCEEDED',progress=100,artifact_id=?,version=version+1 WHERE id=? AND version=?",v.id(),id,j.version())!=1)throw conflict();if(jdbc.update("UPDATE ai_task_outbox SET state='DONE',updated_at=statement_timestamp() WHERE task_id=? AND state='LEASED' AND generation=?",id,j.generation())!=1)throw conflict();var next=row(request,id,false);event(next,"CALLBACK","Non-diagnostic synthetic fixture completed");return receipt(next,j.version());}
 });}
 public StorageProvider.Slice artifact(UUID request,UUID id){return tx.execute(t->{var j=job(request,id,false,true);current(j);if(!j.state().equals("SYNTHETIC_SUCCEEDED"))throw conflict();var bytes=storage.workerArtifactBytes(request,j.artifactId());current(j);audit.append(j.hospitalId(),"AI_TASK_ARTIFACT_V1","SYNTHETIC_AI_TASK",id,null,j.version());return bytes;});}
}
