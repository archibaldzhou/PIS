package com.pis.report;
import com.pis.diagnosis.DiagnosisService;
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
import static com.pis.report.ReportContracts.*;
@Service
public class ReportService {
 private final JdbcTemplate jdbc;private final DiagnosisService diagnosis;private final IdempotentCommands commands;private final Validator validator;private final JsonMapper json;
 public ReportService(JdbcTemplate jdbc,DiagnosisService diagnosis,IdempotentCommands commands,Validator validator,JsonMapper json) { this.jdbc=jdbc;this.diagnosis=diagnosis;this.commands=commands;this.validator=validator;this.json=json; }
 private Revision map(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Revision(r.getObject("id",UUID.class),r.getObject("case_id",UUID.class),r.getLong("version"),r.getString("template_code"),r.getInt("template_version"),json.readTree(r.getString("fields")),r.getLong("assignment_version"),r.getObject("author_id",UUID.class),r.getString("reason"),r.getObject("created_at",OffsetDateTime.class).toInstant()); }
 private Revision current(UUID id) { var rows=jdbc.query("SELECT r.* FROM report_draft d JOIN report_revision r ON r.id=d.revision_id WHERE d.case_id=?",this::map,id);return rows.isEmpty()?null:rows.getFirst(); }
 private DiagnosisService.ReportContext locked(UUID id) { var c=diagnosis.reportContext(id);jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",c.requestId());return diagnosis.reportContext(id); }
 @Transactional(timeout=10) public Detail detail(UUID id) {
  var c=locked(id);var templates=jdbc.query("SELECT * FROM report_template ORDER BY code,version LIMIT 100",(r,i)->new Template(r.getString("code"),r.getInt("version"),r.getString("title"),r.getString("schema_code")));
  return new Detail(c,current(id),templates);
 }
 @Transactional(timeout=10) public History history(UUID id,int page) { locked(id);if(page<1||page>10000) throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_PAGE_INVALID","Invalid history page"); return new History(id,page,jdbc.query("SELECT * FROM report_revision WHERE case_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",this::map,id,(page-1)*20)); }
 public IdempotentCommands.Result save(UUID id,Save input,String key) {
  var errors=validator.validate(input);if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors);
  var initial=diagnosis.reportContext(id);
  return commands.execute(initial.hospitalId(),"REPORT_DRAFT_SAVE_V1",key,Map.of("case",id,"command",input),new IdempotentCommands.Work() {
   public void authorize(CurrentActor.Actor actor) { diagnosis.reportContext(id); }
   public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { diagnosis.reportContext(id); }
   public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
    jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.requestId());var c=diagnosis.reportContext(id);
    if(!id.equals(input.confirmedCaseId())) throw conflict("REPORT_IDENTITY_MISMATCH");
    if(c.assignmentVersion()!=input.assignmentVersion()) throw conflict("VERSION_CONFLICT");
    if(!c.ready()) throw conflict("DIAGNOSIS_NOT_READY");
    var old=current(id);long version=old==null?-1:old.version();if(version!=input.expectedVersion()) throw conflict("VERSION_CONFLICT");
    var schemas=jdbc.queryForList("SELECT schema_code FROM report_template WHERE code=? AND version=?",String.class,input.templateCode(),input.templateVersion());
    if(schemas.isEmpty()) throw conflict("REPORT_TEMPLATE_UNAVAILABLE");ReportSchema.validate(schemas.getFirst(),input.fields());
    UUID revision=UUID.randomUUID();long next=version+1;
    jdbc.update("INSERT INTO report_revision(id,case_id,version,template_code,template_version,fields,assignment_version,author_id,reason) VALUES(?,?,?,?,?,?::jsonb,?,?,?)",revision,id,next,input.templateCode(),input.templateVersion(),json.writeValueAsString(input.fields()),c.assignmentVersion(),actor.id(),input.reason());
    if(old==null) jdbc.update("INSERT INTO report_draft(case_id,version,revision_id) VALUES(?,0,?)",id,revision);
    else if(jdbc.update("UPDATE report_draft SET version=version+1,revision_id=? WHERE case_id=? AND version=?",revision,id,version)!=1) throw conflict("VERSION_CONFLICT");
    return new IdempotentCommands.Mutation(new CommandReceipt(200,"REPORT_DRAFT",id,next),version<0?null:version);
   }
  });
 }
 private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Report draft requires review"); }
}
