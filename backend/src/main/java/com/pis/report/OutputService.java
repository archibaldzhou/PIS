package com.pis.report;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.pis.report.OutputContracts.*;
@Service
public class OutputService {
 private final JdbcTemplate jdbc;private final ReviewService reviews;private final IdempotentCommands commands;private final Validator validator;private final JsonMapper json;private final com.pis.accession.WorkflowAccess access;
 public OutputService(JdbcTemplate jdbc,ReviewService reviews,IdempotentCommands commands,Validator validator,JsonMapper json,com.pis.accession.WorkflowAccess access) { this.jdbc=jdbc;this.reviews=reviews;this.commands=commands;this.validator=validator;this.json=json;this.access=access; }
 private static final String METADATA="SELECT id,case_id,version,signature_id,signature_version,revision_id,draft_version,template_code,template_version,schema_code,dependency_token,renderer_version,font_hash,sha256,byte_size,pages,created_at FROM report_artifact";
 private Artifact map(java.sql.ResultSet r,int i) throws java.sql.SQLException { return new Artifact(r.getObject("id",UUID.class),r.getObject("case_id",UUID.class),r.getLong("version"),r.getObject("signature_id",UUID.class),r.getLong("signature_version"),r.getObject("revision_id",UUID.class),r.getLong("draft_version"),r.getString("template_code"),r.getInt("template_version"),r.getString("schema_code"),r.getString("dependency_token"),r.getString("renderer_version"),r.getString("font_hash"),r.getString("sha256"),r.getInt("byte_size"),r.getInt("pages"),r.getObject("created_at",OffsetDateTime.class).toInstant()); }
 private Artifact find(UUID caseId,UUID signature) { var rows=jdbc.query(METADATA+" WHERE case_id=? AND signature_id=?",this::map,caseId,signature);return rows.isEmpty()?null:rows.getFirst(); }
 private Artifact require(UUID caseId,UUID artifact) { reviews.outputAccess(caseId);var rows=jdbc.query(METADATA+" WHERE case_id=? AND id=?",this::map,caseId,artifact);if(rows.isEmpty()) throw missing();return rows.getFirst(); }
 private long version(UUID artifact) { return jdbc.queryForObject("SELECT version FROM report_output_head WHERE artifact_id=?",Long.class,artifact); }
 private void validate(Object input) { var errors=validator.validate(input);if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors); }
 @Transactional(timeout=10) public Detail detail(UUID id) { var f=reviews.outputFrozen(id);var a=find(id,f.signatureId());return new Detail(id,f.signatureId(),f.signatureVersion(),f.revision().id(),f.dependenciesCurrent(),a,a==null?-1:version(a.id())); }
 private void bind(UUID id,Create input,ReviewService.Frozen f) { if(!id.equals(input.confirmedCaseId())) throw conflict("REPORT_IDENTITY_MISMATCH");if(!f.signatureId().equals(input.signatureId())||f.signatureVersion()!=input.signatureVersion()||!f.revision().id().equals(input.revisionId())) throw conflict("VERSION_CONFLICT"); }
 private SyntheticPdf.Rendered render(ReviewService.Frozen f) {
  var r=f.revision();ReportSchema.validate(f.schemaCode(),r.fields());var sections=new ArrayList<SyntheticPdf.Section>();
  var keys=List.of("gross","microscopy","diagnosis","notes");var labels=List.of("大体描述（人工）","镜下描述（人工）","诊断草稿（人工）","备注（人工）");for(int i=0;i<keys.size();i++)sections.add(new SyntheticPdf.Section(labels.get(i),r.fields().get(keys.get(i)).stringValue()));
  if(f.schemaCode().equals("SYN-STRUCTURED-2")){sections.add(new SyntheticPdf.Section("合成样本计数",r.fields().get("sampleCount").toString()));sections.add(new SyntheticPdf.Section("人工核对",r.fields().get("manualChecked").toString()));}
  try { return new SyntheticPdf().render(new SyntheticPdf.Document(r.caseId(),r.id(),f.signatureId(),f.reviewId(),r.version(),r.templateCode(),r.templateVersion(),f.schemaCode(),f.dependencyToken(),sections)); }
  catch(SyntheticPdf.RenderException e) { throw new ApiException(e.code.equals("REPORT_RENDER_BUSY")?HttpStatus.TOO_MANY_REQUESTS:e.code.equals("REPORT_FONT_UNAVAILABLE")?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.BAD_REQUEST,e.code,"Bounded synthetic PDF could not be produced"); }
 }
 public IdempotentCommands.Result create(UUID id,Create input,String key) {
  validate(input);var initial=reviews.outputFrozen(id);bind(id,input,initial);var existing=find(id,initial.signatureId());
  if(existing==null&&!initial.dependenciesCurrent()) throw conflict("REPORT_OUTPUT_STALE");
  // CPU work and font loading never occupy the command transaction or a connection.
  var rendered=existing==null?render(initial):null;
  return commands.execute(initial.context().hospitalId(),"REPORT_ARTIFACT_ENSURE_V1",key,Map.of("case",id,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor) { reviews.outputAccess(id); }
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { require(id,receipt.resourceId()); }
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.context().requestId());var f=reviews.outputFrozenLocked(id);bind(id,input,f);var a=find(id,f.signatureId());
    if(a!=null) return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_ARTIFACT",a.id(),a.version()),null);
    if(!f.dependenciesCurrent()||!f.dependencyToken().equals(initial.dependencyToken())||rendered==null) throw conflict("REPORT_OUTPUT_STALE");
    UUID artifact=UUID.randomUUID();var r=f.revision();byte[] bytes=rendered.bytes();
    jdbc.update("INSERT INTO report_artifact(id,case_id,signature_id,signature_version,review_id,revision_id,draft_version,template_code,template_version,schema_code,field_snapshot,dependency_token,renderer_version,font_hash,pdf,sha256,byte_size,pages,creation_reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?)",artifact,id,f.signatureId(),f.signatureVersion(),f.reviewId(),r.id(),r.version(),r.templateCode(),r.templateVersion(),f.schemaCode(),json.writeValueAsString(r.fields()),f.dependencyToken(),SyntheticPdf.VERSION,rendered.fontHash(),bytes,rendered.sha256(),bytes.length,rendered.pages(),input.reason(),actor.id());
    jdbc.update("INSERT INTO report_output_head(artifact_id) VALUES(?)",artifact);
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_ARTIFACT",artifact,0),null);
   }
  });
 }
 private void bind(UUID id,Operation input,Artifact a) { if(!id.equals(input.confirmedCaseId())) throw conflict("REPORT_IDENTITY_MISMATCH");if(input.artifactVersion()!=a.version()||!input.sha256().equals(a.sha256())) throw conflict("VERSION_CONFLICT"); }
 public record Binary(byte[] bytes,Artifact artifact,boolean replayed) { }
 public Binary bytes(UUID id,UUID artifact,Operation input,String key,Kind kind) {
  if(kind!=Kind.PREVIEW&&kind!=Kind.DOWNLOAD) throw new IllegalArgumentException("Binary purpose required");var holder=new Binary[1];var result=operate(id,artifact,input,key,kind,holder);return new Binary(holder[0].bytes(),holder[0].artifact(),result.replayed());
 }
 public IdempotentCommands.Result record(UUID id,UUID artifact,Operation input,String key,Kind kind) {
  if(kind==Kind.PREVIEW||kind==Kind.DOWNLOAD) throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_OUTPUT_ACTION_INVALID","Use audited binary endpoint");return operate(id,artifact,input,key,kind,null);
 }
 private Binary binary(Artifact a) {
  byte[] bytes=jdbc.queryForObject("SELECT pdf FROM report_artifact WHERE id=? AND case_id=?",byte[].class,a.id(),a.caseId());
  if(bytes==null||bytes.length!=a.byteSize()||!SyntheticPdf.sha256(bytes).equals(a.sha256())) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"REPORT_ARTIFACT_INTEGRITY","Stored artifact integrity check failed");return new Binary(bytes,a,false);
 }
 private void printPermit(UUID id,Kind kind) {
  if(kind==Kind.PREVIEW||kind==Kind.DOWNLOAD)return;
  var c=reviews.outputAccess(id);
  try { access.require(c.scopeId(),kind==Kind.REPRINT_REQUEST?com.pis.accession.WorkflowAccess.Permission.REPRINT:com.pis.accession.WorkflowAccess.Permission.PRINT); }
  catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); }
 }
 private IdempotentCommands.Result operate(UUID id,UUID artifact,Operation input,String key,Kind kind,Binary[] binary) {
  validate(input);boolean read=kind==Kind.PREVIEW||kind==Kind.DOWNLOAD;boolean result=kind.name().startsWith("USER_REPORTED_");
  if((kind==Kind.REPRINT_REQUEST||result)!=(input.requestId()!=null)) throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_OUTPUT_ACTION_INVALID","Explicit originating request required");
  var initial=reviews.outputAccess(id);printPermit(id,kind);bind(id,input,require(id,artifact));
  return commands.execute(initial.hospitalId(),"REPORT_OUTPUT_"+kind+"_V1",key,Map.of("case",id,"artifact",artifact,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor) { printPermit(id,kind);bind(id,input,require(id,artifact)); }
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",initial.requestId());printPermit(id,kind);var a=require(id,artifact);if(binary!=null)binary[0]=binary(a); }
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.requestId());printPermit(id,kind);var a=require(id,artifact);bind(id,input,a);long old=version(artifact);
    if(!read&&old!=input.expectedVersion()) throw conflict("VERSION_CONFLICT");
    if((kind==Kind.PRINT_REQUEST||kind==Kind.REPRINT_REQUEST)&&!reviews.outputFrozenLocked(id).dependenciesCurrent()) throw conflict("REPORT_OUTPUT_STALE");
    if(input.requestId()!=null) {
     var requests=jdbc.queryForList("SELECT actor_id FROM report_output_event WHERE artifact_id=? AND id=? AND kind IN ('PRINT_REQUEST','REPRINT_REQUEST')",UUID.class,artifact,input.requestId());
     if(requests.isEmpty()||result&&!requests.getFirst().equals(actor.id())) throw missing();
     if(result&&jdbc.queryForObject("SELECT count(*) FROM report_output_event WHERE request_id=? AND kind LIKE 'USER_REPORTED_%'",Long.class,input.requestId())>0) throw conflict("REPORT_PRINT_RESULT_EXISTS");
    }
    UUID event=UUID.randomUUID();long next=old+1;
    jdbc.update("INSERT INTO report_output_event(id,artifact_id,version,kind,request_id,actor_id,reason) VALUES(?,?,?,?,?,?,?)",event,artifact,next,kind.name(),input.requestId(),actor.id(),input.reason());
    if(jdbc.update("UPDATE report_output_head SET version=version+1 WHERE artifact_id=? AND version=?",artifact,old)!=1) throw conflict("VERSION_CONFLICT");
    if(binary!=null)binary[0]=binary(a);
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_OUTPUT_EVENT",event,next),old<0?null:old);
   }
  });
 }
 @Transactional(timeout=10) public History history(UUID id,UUID artifact,int page) {
  var c=reviews.outputAccess(id);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());require(id,artifact);if(page<1||page>10000) throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_PAGE_INVALID","Invalid output history page");
  return new History(id,artifact,page,jdbc.query("SELECT * FROM report_output_event WHERE artifact_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("version"),Kind.valueOf(r.getString("kind")),r.getObject("request_id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),artifact,(page-1)*20));
 }
 private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"REPORT_OUTPUT_NOT_FOUND","Report output unavailable"); }
 private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Report output requires current snapshot and authorization"); }
}
