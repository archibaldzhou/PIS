package com.pis.label;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.label.LabelContracts.*;
@Service
public class LabelService {
    private final JdbcTemplate jdbc; private final WorkflowAccess access; private final List<LabelSubjectProvider> providers;
    private final IdempotentCommands commands; private final Validator validator;
    public LabelService(JdbcTemplate jdbc,WorkflowAccess access,List<LabelSubjectProvider> providers,IdempotentCommands commands,Validator validator) {
        this.jdbc=jdbc; this.access=access; this.providers=List.copyOf(providers); this.commands=commands; this.validator=validator;
    }
    private final RowMapper<Job> mapper=(r,i)->new Job(r.getObject("id",UUID.class),r.getObject("container_id",UUID.class),r.getString("barcode"),r.getObject("parent_job_id",UUID.class),r.getString("template_version"),r.getString("state"),r.getLong("version"),r.getInt("attempts"),r.getLong("request_version"),r.getLong("container_version"),r.getObject("patient_id",UUID.class),r.getString("patient_label"),r.getString("encounter_number"),r.getString("request_number"),r.getString("case_number"),r.getString("site"),r.getString("laterality"),r.getString("reason"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("material_id",UUID.class),r.getObject("target_id",UUID.class));
    private LabelSubjectProvider.Subject subject(UUID id,Permission permission) { return subject(id,permission,false); }
    private LabelSubjectProvider.Subject subject(UUID id,Permission permission,boolean registration) {
        access.actor(); var found=providers.stream().map(p->registration?p.findForRegistration(id):p.find(id)).flatMap(java.util.Optional::stream).toList();
        if(found.isEmpty()) throw missing(); if(found.size()!=1) throw conflict("LABEL_IDENTITY_MISMATCH"); var s=found.getFirst();
        try { access.require(s.scopeId(),permission); } catch(org.springframework.security.access.AccessDeniedException error) { throw missing(); }
        return s;
    }
    private Job row(UUID id) { access.actor(); var rows=jdbc.query("SELECT * FROM label_job WHERE id=?",mapper,id); if(rows.isEmpty()) throw missing(); return rows.getFirst(); }
    public record ContainerView(UUID containerId,long requestVersion,long containerVersion,String requestState,List<Job> jobs) { }
    public record MaterialView(UUID materialId,long requestVersion,long materialVersion,String state,List<Job> jobs) { }
    @Transactional(timeout=10)
    public ContainerView container(UUID id) {
        var s=subject(id,Permission.PRINT); if(s.containerId()==null) throw missing();
        return new ContainerView(id,s.requestVersion(),s.targetVersion(),s.requestState(),jobs(id));
    }
    @Transactional(timeout=10)
    public MaterialView material(UUID id) {
        var s=subject(id,Permission.PRINT); if(s.materialId()==null) throw missing();
        return new MaterialView(id,s.requestVersion(),s.targetVersion(),s.active()?"ACTIVE":"VOID",jobs(id));
    }
    private List<Job> jobs(UUID id) { return jdbc.query("SELECT * FROM label_job WHERE target_id=? ORDER BY created_at DESC,id DESC LIMIT 100",mapper,id); }
    @Transactional(timeout=10)
    public View view(UUID id) {
        var job=row(id); var s=subject(job.targetId(),Permission.PRINT);
        jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",s.requestId()); s=subject(job.targetId(),Permission.PRINT);
        if(s.materialId()!=null&&!s.active()) throw conflict("MATERIAL_INACTIVE"); job=row(id);
        var events=jdbc.query("SELECT * FROM label_job_event WHERE job_id=? ORDER BY job_version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("job_version"),r.getString("action"),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
        return new View(job,events);
    }
    @Transactional(timeout=10)
    public Checked verify(UUID id,Verify input) { validate(input); var job=view(id).job(); if(job.containerId()==null||!LabelBarcode.valid(input.barcode())||!job.containerId().equals(input.containerId())||!job.barcode().equals(input.barcode())) throw conflict("LABEL_IDENTITY_MISMATCH"); return new Checked(true); }
    @Transactional(timeout=10)
    public Checked verifyMaterial(UUID id,MaterialVerify input) { validate(input); var job=view(id).job(); if(job.materialId()==null||!LabelBarcode.valid(input.barcode())||!job.materialId().equals(input.materialId())||!job.barcode().equals(input.barcode())) throw conflict("LABEL_IDENTITY_MISMATCH"); return new Checked(true); }
    /** Identity allocation is part of the caller's material creation transaction, independent of PRINT. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void registerMaterialIdentity(UUID id,String barcode) {
        var s=subject(id,Permission.MATERIAL,true); if(s.materialId()==null||!s.active()||!LabelBarcode.valid(barcode)) throw conflict("LABEL_IDENTITY_MISMATCH");
        jdbc.update("INSERT INTO label_identity(material_id,hospital_id,request_id,barcode) VALUES(?,?,?,?)",id,s.hospitalId(),s.requestId(),barcode);
    }
    private void validate(Object input) { var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors); }
    public IdempotentCommands.Result create(UUID id,Create input,String key) { if(subject(id,Permission.PRINT).containerId()==null) throw missing(); return execute(id,null,input,key,"CREATE"); }
    public IdempotentCommands.Result createMaterial(UUID id,MaterialCreate input,String key) { if(subject(id,Permission.PRINT).materialId()==null) throw missing(); return execute(id,null,input,key,"CREATE"); }
    public IdempotentCommands.Result change(UUID id,Change input,String key,String action) {
        if(!List.of("REPRINT","FAIL","RETRY","CANCEL").contains(action)) throw new IllegalArgumentException("Unknown label action");
        return execute(row(id).targetId(),id,input,key,action);
    }
    private IdempotentCommands.Result execute(UUID target,UUID jobId,Object input,String key,String action) {
        validate(input); var permission=action.equals("REPRINT")?Permission.REPRINT:Permission.PRINT; var initial=subject(target,permission);
        return commands.execute(initial.hospitalId(),"LABEL_"+action+"_V1",key,Map.of("target",jobId==null?target:jobId,"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { subject(target,permission); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { subject(row(receipt.resourceId()).targetId(),permission); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",initial.requestId());
                if(initial.containerId()!=null) jdbc.queryForList("SELECT id FROM specimen_container WHERE id=? FOR UPDATE",target);
                var s=subject(target,permission);
                if(!s.active()&&!action.equals("CANCEL")) throw conflict(s.materialId()==null?"LABEL_REQUIRES_RECEIVED":"MATERIAL_INACTIVE");
                UUID resultId; long version; Long previous=null;
                if(action.equals("CREATE")) {
                    long rv=input instanceof Create c?c.requestVersion():((MaterialCreate)input).requestVersion();
                    long tv=input instanceof Create c?c.containerVersion():((MaterialCreate)input).materialVersion();
                    if(s.requestVersion()!=rv||s.targetVersion()!=tv) throw conflict("VERSION_CONFLICT");
                    if(jdbc.queryForObject("SELECT count(*) FROM label_job WHERE target_id=? AND parent_job_id IS NULL",Long.class,target)>0) throw conflict("LABEL_USE_REPRINT");
                    var identities=jdbc.queryForList("SELECT barcode FROM label_identity WHERE target_id=?",String.class,target);
                    String barcode;
                    if(identities.isEmpty()) {
                        if(s.materialId()!=null) throw conflict("LABEL_IDENTITY_MISMATCH"); barcode=LabelBarcode.create(UUID.randomUUID());
                        jdbc.update("INSERT INTO label_identity(container_id,hospital_id,request_id,barcode) VALUES(?,?,?,?)",target,s.hospitalId(),s.requestId(),barcode);
                    } else barcode=identities.getFirst();
                    resultId=UUID.randomUUID(); version=0;
                    jdbc.update("""
                        INSERT INTO label_job(id,hospital_id,request_id,container_id,material_id,barcode,template_version,state,request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,reason,created_by)
                        VALUES(?,?,?,?,?,?,?,'PREVIEW_READY',?,?,?,?,?,?,?,?,?,'Initial synthetic label preview',?)
                        """,resultId,s.hospitalId(),s.requestId(),s.containerId(),s.materialId(),barcode,s.materialId()==null?"SYN-CONTAINER-1":"SYN-MATERIAL-1",rv,tv,s.patientId(),s.patientLabel(),s.encounterNumber(),s.requestNumber(),s.caseNumber(),s.site(),s.laterality(),actor.id());
                    event(resultId,0,"CREATE","Initial synthetic label preview",actor.id());
                } else {
                    jdbc.queryForList("SELECT id FROM label_job WHERE id=? FOR UPDATE",jobId); var job=row(jobId); var change=(Change)input;
                    if(job.version()!=change.expectedVersion()) throw conflict("VERSION_CONFLICT");
                    if(action.equals("REPRINT")) {
                        resultId=UUID.randomUUID(); version=0;
                        if(jdbc.update("UPDATE label_job SET version=version+1 WHERE id=? AND version=?",jobId,job.version())!=1) throw conflict("VERSION_CONFLICT");
                        event(jobId,job.version()+1,"REPRINT",change.reason(),actor.id());
                        jdbc.update("""
                            INSERT INTO label_job(id,hospital_id,request_id,container_id,material_id,barcode,parent_job_id,template_version,state,request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,reason,created_by)
                            SELECT ?,hospital_id,request_id,container_id,material_id,barcode,id,template_version,'PREVIEW_READY',request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,?,? FROM label_job WHERE id=?
                            """,resultId,change.reason(),actor.id(),jobId); event(resultId,0,"CREATE",change.reason(),actor.id());
                    } else {
                        String next;
                        if(action.equals("FAIL")&&job.state().equals("PREVIEW_READY")) next="FAILED";
                        else if(action.equals("RETRY")&&job.state().equals("FAILED")&&job.attempts()<1000) next="PREVIEW_READY";
                        else if(action.equals("CANCEL")&&!job.state().equals("CANCELLED")) next="CANCELLED";
                        else throw conflict("LABEL_STATE_CONFLICT");
                        resultId=jobId; previous=job.version(); version=job.version()+1;
                        if(jdbc.update("UPDATE label_job SET state=?,version=version+1,attempts=attempts+? WHERE id=? AND version=?",next,action.equals("RETRY")?1:0,jobId,job.version())!=1) throw conflict("VERSION_CONFLICT"); event(jobId,version,action,change.reason(),actor.id());
                    }
                }
                return new IdempotentCommands.Mutation(new CommandReceipt(action.equals("CREATE")||action.equals("REPRINT")?201:200,"LABEL_JOB",resultId,version),previous);
            }
        });
    }
    private void event(UUID job,long version,String action,String reason,UUID actor) { jdbc.update("INSERT INTO label_job_event(job_id,job_version,action,reason,actor_id) VALUES(?,?,?,?,?)",job,version,action,reason,actor); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"LABEL_NOT_FOUND","Label resource is not available"); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Label operation requires refresh or review"); }
}
