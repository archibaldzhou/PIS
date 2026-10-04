package com.pis.ai;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.RequestService;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.idempotency.*;
import com.pis.viewer.ViewerService;
import com.pis.roi.RoiService;
import com.pis.scan.ScanService;
import jakarta.validation.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import static com.pis.ai.AiContracts.*;
@Service
public class AiRegistryService {
 private final RequestService requests;private final JdbcTemplate jdbc;private final WorkflowAccess access;private final ViewerService viewer;private final RoiService roi;private final ScanService scans;private final IdempotentCommands commands;private final AuditRecorder audit;private final Validator validator;private final TransactionTemplate tx;private final JsonMapper json=JsonMapper.builder().build();
 public AiRegistryService(RequestService requests,JdbcTemplate jdbc,WorkflowAccess access,ViewerService viewer,RoiService roi,ScanService scans,IdempotentCommands commands,AuditRecorder audit,Validator validator,PlatformTransactionManager manager){this.requests=requests;this.jdbc=jdbc;this.access=access;this.viewer=viewer;this.roi=roi;this.scans=scans;this.commands=commands;this.audit=audit;this.validator=validator;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
 private record Scope(UUID id,UUID hospital,UUID actor,Permissions permissions){}
 private Scope scope(UUID id,String permission){
  try{var s=access.require(id,WorkflowAccess.Permission.READ);var actor=access.actor();
   if(TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForList("SELECT user_id FROM ai_registry_grant WHERE scope_id=? AND user_id=? FOR SHARE",id,actor.id());
   actor=access.actor();var rows=jdbc.query("SELECT can_register,can_validate,can_assess FROM ai_registry_grant WHERE scope_id=? AND user_id=? AND qualification='SYN-AI-CONTRACT-1' AND valid_until>statement_timestamp() AND revoked_at IS NULL",(r,i)->new Permissions(r.getBoolean(1),r.getBoolean(2),r.getBoolean(3),false,false),id,actor.id());
   if(rows.isEmpty())throw missing();var p=rows.getFirst();if(!(switch(permission){case "REGISTER"->p.register();case "VALIDATE"->p.validate();case "ASSESS"->p.assess();default->p.register()||p.validate()||p.assess();}))throw missing();return new Scope(id,s.hospitalId(),actor.id(),p);
  }catch(org.springframework.security.access.AccessDeniedException e){throw missing();}
 }
 private void valid(Object input){var errors=validator.validate(input);if(!errors.isEmpty())throw new ConstraintViolationException(errors);}
 private org.springframework.jdbc.core.RowMapper<Model> modelMapper(){return (r,i)->new Model(r.getObject("id",UUID.class),r.getObject("model_id",UUID.class),r.getObject("scope_id",UUID.class),r.getString("code"),r.getLong("ordinal"),r.getLong("head"),r.getLong("revision"),r.getString("state"),json.readValue(r.getString("metadata"),Metadata.class));}
 private static final String MODEL="SELECT v.*,s.scope_id,s.code,s.head,h.revision,h.state FROM ai_model_version v JOIN ai_model_series s ON s.id=v.model_id JOIN ai_model_state h ON h.version_id=v.id ";
 private Model model(UUID scope,UUID version){var rows=jdbc.query(MODEL+"WHERE s.scope_id=? AND v.id=?",modelMapper(),scope,version);if(rows.isEmpty())throw missing();var m=rows.getFirst();if(TransactionSynchronizationManager.isActualTransactionActive()){jdbc.queryForList("SELECT id FROM ai_model_series WHERE id=? FOR SHARE",m.modelId());jdbc.queryForList("SELECT version_id FROM ai_model_state WHERE version_id=? FOR SHARE",version);m=jdbc.queryForObject(MODEL+"WHERE s.scope_id=? AND v.id=?",modelMapper(),scope,version);}return m;}
 public Catalog catalog(UUID scope,int page){if(page<1||page>50)throw problem(HttpStatus.BAD_REQUEST,"AI_PAGE");return tx.execute(t->{var c=scope(scope,"READ");var rows=jdbc.query(MODEL+"WHERE s.scope_id=? ORDER BY s.code,v.ordinal DESC LIMIT 20 OFFSET ?",modelMapper(),scope,(page-1)*20);audit.append(c.hospital(),"AI_CATALOG_V1","WORKFLOW_SCOPE",scope,null,0);return new Catalog(scope,page,rows,c.permissions(),"CONTRACT_ONLY_NO_EXECUTION");});}
 public List<Map<String,Object>> history(UUID scope,UUID version){return tx.execute(t->{var c=scope(scope,"READ");model(scope,version);audit.append(c.hospital(),"AI_HISTORY_V1","AI_MODEL_VERSION",version,null,0);return jdbc.queryForList("SELECT revision,state,reason,actor_id,created_at FROM ai_model_event WHERE version_id=? ORDER BY revision DESC LIMIT 100",version);});}
 public IdempotentCommands.Result register(UUID scope,Register input,String key){valid(input);try{AiPolicy.validate(input.metadata());}catch(IllegalArgumentException e){throw problem(HttpStatus.BAD_REQUEST,"AI_SCHEMA");}var initial=scope(scope,"REGISTER");
  return commands.execute(initial.hospital(),"AI_REGISTER_V1",key,Map.of("scope",scope,"input",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor a){scope(scope,"REGISTER");}
   public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){authorize(a);model(scope,r.resourceId());}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){scope(scope,"REGISTER");jdbc.update("INSERT INTO ai_model_series(id,scope_id,hospital_id,code) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",input.modelId(),scope,initial.hospital(),input.code());
    var rows=jdbc.queryForList("SELECT id FROM ai_model_series WHERE id=? AND scope_id=? AND code=? FOR UPDATE",UUID.class,input.modelId(),scope,input.code());if(rows.isEmpty())throw conflict();
    if(jdbc.update("UPDATE ai_model_series SET head=head+1 WHERE id=? AND head=? AND head<99",input.modelId(),input.expectedHead())!=1)throw conflict();
    UUID id=UUID.randomUUID();long ordinal=input.expectedHead()+1;
    jdbc.update("INSERT INTO ai_model_version(id,model_id,ordinal,metadata,digest,actor_id,reason) VALUES(?,?,?,?::jsonb,?,?,?)",id,input.modelId(),ordinal,json.writeValueAsString(input.metadata()),input.metadata().digest(),a.id(),input.reason());
    jdbc.update("INSERT INTO ai_model_state(version_id) VALUES(?)",id);event(id,0,"DRAFT",a.id(),input.reason());return mutation("AI_MODEL_VERSION",id,ordinal);
   }});
 }
 private void event(UUID id,long version,String state,UUID actor,String reason){jdbc.update("INSERT INTO ai_model_event(version_id,revision,state,actor_id,reason) VALUES(?,?,?,?,?)",id,version,state,actor,reason);}
 public IdempotentCommands.Result change(UUID scope,UUID version,StateChange input,String key){valid(input);if(!Set.of("VALIDATION_ONLY","DISABLED","RETIRED").contains(input.state()))throw problem(HttpStatus.BAD_REQUEST,"AI_STATE");var c=scope(scope,"VALIDATE");
  return commands.execute(c.hospital(),"AI_STATE_V1",key,Map.of("scope",scope,"version",version,"input",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor a){scope(scope,"VALIDATE");}
   public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){authorize(a);model(scope,version);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){scope(scope,"VALIDATE");var ids=jdbc.queryForList("SELECT s.id FROM ai_model_series s JOIN ai_model_version v ON v.model_id=s.id WHERE s.scope_id=? AND v.id=? FOR UPDATE OF s",UUID.class,scope,version);if(ids.isEmpty())throw missing();var m=model(scope,version);
    if(m.state().equals("RETIRED")||m.state().equals(input.state())||input.state().equals("VALIDATION_ONLY")&&m.ordinal()!=m.head())throw conflict();
    if(jdbc.update("UPDATE ai_model_state SET state=?,revision=revision+1 WHERE version_id=? AND revision=? AND revision<99",input.state(),version,input.expectedVersion())!=1)throw conflict();event(version,input.expectedVersion()+1,input.state(),a.id(),input.reason());return mutation("AI_MODEL_STATE",version,input.expectedVersion()+1);
   }});
 }
 private record Context(Scope scope,ViewerService.Content image,long publication,String scanner,RoiService.Calibration calibration){}
 private Context context(UUID request,UUID scan,long publication,String hash){var image=viewer.manifest(request,scan,publication).content();if(hash!=null&&!image.manifestHash().equals(hash))throw conflict();var scopeId=requests.authorizedScope(request).id();var s=scope(scopeId,"ASSESS");if(!s.hospital().equals(image.hospitalId()))throw missing();var j=scans.detail(request,scan);var cal=roi.view(request,scan,publication).calibration();return new Context(s,image,publication,j.scannerCode(),cal);}
 private ProfileView profile(UUID scan){var rows=jdbc.query("SELECT * FROM ai_scan_profile WHERE scan_id=? ORDER BY version DESC LIMIT 1",(r,i)->new ProfileView(r.getLong("version"),r.getLong("publication_version"),r.getString("manifest_hash"),json.readValue(r.getString("profile"),Profile.class)),scan);return rows.isEmpty()?null:rows.getFirst();}
 public ScanView scan(UUID request,UUID scan,long pub){return tx.execute(t->{var c=context(request,scan,pub,null);audit.append(c.scope().hospital(),"AI_SCAN_READ_V1","SCAN_IMPORT",scan,null,pub);return new ScanView(request,scan,c.scope().id(),pub,c.image().manifestHash(),c.scanner(),profile(scan),c.calibration()==null?null:c.calibration().version(),c.calibration()==null?"UNKNOWN":"SYNTHETIC_TEST_ONLY");});}
 public IdempotentCommands.Result profile(UUID request,UUID scan,ProfileInput input,String key){valid(input);var c=tx.execute(t->context(request,scan,input.publicationVersion(),input.manifestHash()));
  return commands.execute(c.scope().hospital(),"AI_PROFILE_V1",key,Map.of("request",request,"scan",scan,"input",input),new IdempotentCommands.Work(){
   public void authorize(CurrentActor.Actor a){context(request,scan,input.publicationVersion(),input.manifestHash());}
   public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){authorize(a);}
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){context(request,scan,input.publicationVersion(),input.manifestHash());var old=profile(scan);if((old==null?-1:old.version())!=input.expectedVersion())throw conflict();long next=input.expectedVersion()+1;jdbc.update("INSERT INTO ai_scan_profile(scan_id,version,publication_version,manifest_hash,profile,actor_id,reason) VALUES(?,?,?,?,?::jsonb,?,?)",scan,next,input.publicationVersion(),input.manifestHash(),json.writeValueAsString(input.profile()),a.id(),input.reason());return mutation("AI_SCAN_PROFILE",scan,next);}
  });
 }
 private Decision decision(UUID request,UUID scan,Assess input){var c=context(request,scan,input.publicationVersion(),input.manifestHash());var m=model(c.scope().id(),input.modelVersionId());var p=profile(scan);
  if(p==null)throw problem(HttpStatus.CONFLICT,"AI_PROFILE_REQUIRED");if(p.version()!=input.profileVersion()||p.publicationVersion()!=input.publicationVersion()||!p.manifestHash().equals(input.manifestHash())||m.stateVersion()!=input.stateVersion()||!Objects.equals(input.calibrationVersion(),c.calibration()==null?null:c.calibration().version()))throw conflict();
  var result=AiPolicy.decide(m,p.profile(),c.scanner(),c.calibration()!=null);var image=c.image();
  return new Decision(request,scan,c.scope().id(),image.objectId(),image.objectHash(),image.scanVersion(),image.manifestHash(),input.publicationVersion(),m.id(),m.metadata().digest(),m.ordinal(),m.head(),m.stateVersion(),p.version(),input.calibrationVersion(),result.outcome(),result.reasons(),false,"MISSING_NO_CLINICAL_OR_REGULATORY_APPROVAL");
 }
 public IdempotentCommands.Result assess(UUID request,UUID scan,Assess input,String key){valid(input);var c=tx.execute(t->context(request,scan,input.publicationVersion(),input.manifestHash()));return commands.execute(c.scope().hospital(),"AI_ASSESS_V1",key,Map.of("request",request,"scan",scan,"input",input),new IdempotentCommands.Work(){
  public void authorize(CurrentActor.Actor a){context(request,scan,input.publicationVersion(),input.manifestHash());}
  public void authorizeReplay(CurrentActor.Actor a,CommandReceipt r){authorize(a);var before=snapshot(request,scan,r.resourceId());if(!before.equals(decision(request,scan,input)))throw conflict();}
  public IdempotentCommands.Mutation mutate(CurrentActor.Actor a){var d=decision(request,scan,input);UUID id=UUID.randomUUID();jdbc.update("INSERT INTO ai_assessment(id,scope_id,scan_id,profile_version,model_version_id,snapshot,reason,actor_id) VALUES(?,?,?,?,?,?::jsonb,?,?)",id,d.scopeId(),scan,d.profileVersion(),d.modelVersionId(),json.writeValueAsString(d),input.reason(),a.id());return mutation("AI_ASSESSMENT",id,0);}
 });}
 private Decision snapshot(UUID request,UUID scan,UUID id){var rows=jdbc.queryForList("SELECT snapshot::text FROM ai_assessment WHERE id=? AND scan_id=?",String.class,id,scan);if(rows.isEmpty())throw missing();var d=json.readValue(rows.getFirst(),Decision.class);if(!d.requestId().equals(request)||!d.scanId().equals(scan))throw missing();return d;}
 public Decision assessment(UUID request,UUID scan,UUID id){return tx.execute(t->{// Gate the requested resource before looking up the assessment ID.
  viewer.annotationHistory(request,scan);UUID scopeId=requests.authorizedScope(request).id();var c=scope(scopeId,"ASSESS");var d=snapshot(request,scan,id);var input=new Assess(d.modelVersionId(),d.stateVersion(),d.profileVersion(),d.publicationVersion(),d.manifestHash(),d.calibrationVersion(),"Read current synthetic contract");if(!d.equals(decision(request,scan,input)))throw conflict();audit.append(c.hospital(),"AI_ASSESS_READ_V1","AI_ASSESSMENT",id,null,0);return d;});}
 public record WorkerBinding(Decision decision,String preprocessing,String configDigest,UUID caseId){}
 public WorkerBinding workerBinding(UUID request,UUID scan,UUID assessment){return tx.execute(t->{var d=assessment(request,scan,assessment);var m=model(d.scopeId(),d.modelVersionId());if(d.executionAllowed()||!d.outcome().equals("VALIDATION_ONLY_APPLICABLE"))throw conflict();return new WorkerBinding(d,m.metadata().preprocessing(),com.pis.scan.ScanFormat.sha(json.writeValueAsString(m.metadata()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),scans.detail(request,scan).caseId());});}
 // Expected dependency changes are data, not exceptions crossing participating transaction boundaries.
 // The history/QC boundary holds the request lock; model() holds series/state locks through commit.
 public boolean workerBindingCurrent(UUID request,UUID scan,UUID assessment,WorkerBinding expected){return tx.execute(t->{
  var image=viewer.annotationHistory(request,scan);var c=scope(requests.authorizedScope(request).id(),"ASSESS");var d=snapshot(request,scan,assessment);
  if(!expected.decision().equals(d)||!image.manifestHash().equals(d.manifestHash())||!viewer.publicationCurrent(request,scan,d.publicationVersion()))return false;
  var m=model(c.id(),d.modelVersionId());var p=profile(scan);
  if(!m.state().equals("VALIDATION_ONLY")||m.head()!=d.modelHead()||m.ordinal()!=d.modelOrdinal()||m.stateVersion()!=d.stateVersion()||!m.metadata().digest().equals(d.modelDigest())||p==null||p.version()!=d.profileVersion()||p.publicationVersion()!=d.publicationVersion()||!p.manifestHash().equals(d.manifestHash()))return false;
  var cal=roi.view(request,scan,d.publicationVersion()).calibration();if(!Objects.equals(d.calibrationVersion(),cal==null?null:cal.version()))return false;
  return expected.equals(workerBinding(request,scan,assessment));
 });}
 public record DependencyStatus(String epoch,List<String> reasons,long modelHead,long modelStateVersion,String modelState,long scanHead,long qcVersion,String qcState){}
 /** Versioned facts for authorized impact history. Never grants execution or consumes revoked pixels. */
 public DependencyStatus dependencyStatus(UUID request,UUID scan,WorkerBinding expected){return tx.execute(t->{
  var image=viewer.annotationHistory(request,scan);var c=scope(requests.authorizedScope(request).id(),"ASSESS");var d=expected.decision();
  if(!request.equals(d.requestId())||!scan.equals(d.scanId())||!c.id().equals(d.scopeId()))throw missing();
  var q=viewer.publicationStatus(request,scan);var m=model(c.id(),d.modelVersionId());var p=profile(scan);var reasons=new ArrayList<String>();
  if(!m.state().equals("VALIDATION_ONLY"))reasons.add("MODEL_DISABLED_OR_RETIRED");
  if(m.stateVersion()!=d.stateVersion()||m.head()!=d.modelHead()||!m.metadata().digest().equals(d.modelDigest()))reasons.add("MODEL_VERSION_CHANGED");
  if(q.scanHead()!=scans.detail(request,scan).ordinal())reasons.add("SCAN_RESCANNED");
  if(!q.current()||q.qcVersion()!=d.publicationVersion())reasons.add("QC_OR_IDENTITY_CHANGED");
  if(!image.manifestHash().equals(d.manifestHash())||p==null||p.version()!=d.profileVersion())reasons.add("INPUT_VERSION_CHANGED");
  var calibration=roi.calibrationHistory(request,scan);if(!Objects.equals(d.calibrationVersion(),calibration==null?null:calibration.version()))reasons.add("CALIBRATION_CHANGED");
  String epoch=com.pis.scan.ScanFormat.sha(json.writeValueAsString(List.of(expected,m,q,p==null?"MISSING":p,calibration==null?"UNKNOWN":calibration)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  return new DependencyStatus(epoch,List.copyOf(reasons),m.head(),m.stateVersion(),m.state(),q.scanHead(),q.qcVersion(),q.state());
 });}
 public void authorizeTaskHistory(UUID request,UUID scan){tx.executeWithoutResult(t->{viewer.annotationHistory(request,scan);scope(requests.authorizedScope(request).id(),"ASSESS");});}
 private static IdempotentCommands.Mutation mutation(String type,UUID id,long version){return new IdempotentCommands.Mutation(new CommandReceipt(200,type,id,version),null);}
 private static ApiException missing(){return problem(HttpStatus.NOT_FOUND,"AI_NOT_FOUND");}
 private static ApiException conflict(){return problem(HttpStatus.CONFLICT,"AI_CONFLICT");}
 private static ApiException problem(HttpStatus status,String code){return new ApiException(status,code,"Synthetic AI contract unavailable; recheck exact versions and current authorization; no execution permitted");}
}
