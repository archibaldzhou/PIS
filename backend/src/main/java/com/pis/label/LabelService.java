package com.pis.label;
import com.pis.accession.RequestService;
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
import org.springframework.transaction.annotation.Transactional;
import static com.pis.label.LabelContracts.*;

@Service
public class LabelService {
    private final JdbcTemplate jdbc; private final RequestService requests; private final WorkflowAccess access;
    private final IdempotentCommands commands; private final Validator validator;
    public LabelService(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access,IdempotentCommands commands,Validator validator) {
        this.jdbc=jdbc; this.requests=requests; this.access=access; this.commands=commands; this.validator=validator;
    }
    private final RowMapper<Job> mapper=(r,i)->new Job(r.getObject("id",UUID.class),r.getObject("container_id",UUID.class),r.getString("barcode"),r.getObject("parent_job_id",UUID.class),r.getString("template_version"),r.getString("state"),r.getLong("version"),r.getInt("attempts"),r.getLong("request_version"),r.getLong("container_version"),r.getObject("patient_id",UUID.class),r.getString("patient_label"),r.getString("encounter_number"),r.getString("request_number"),r.getString("case_number"),r.getString("site"),r.getString("laterality"),r.getString("reason"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class).toInstant());
    private UUID requestId(UUID cid) {
        access.actor();
        var ids=jdbc.queryForList("SELECT request_id FROM specimen_container WHERE id=?",UUID.class,cid);
        if(ids.isEmpty()) throw missing(); return ids.getFirst();
    }
    private Job row(UUID id) {
        access.actor(); var rows=jdbc.query("SELECT * FROM label_job WHERE id=?",mapper,id);
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private WorkflowAccess.Scope authorize(UUID cid,Permission permission) {
        var detail=requests.detail(requestId(cid));
        try { return access.require(detail.scopeId(),permission); }
        catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); }
    }
    public record ContainerView(UUID containerId,long requestVersion,long containerVersion,String requestState,List<Job> jobs) { }
    @Transactional(timeout=10)
    public ContainerView container(UUID cid) {
        authorize(cid,Permission.PRINT); var request=requests.detail(requestId(cid));
        return new ContainerView(cid,request.version(),jdbc.queryForObject("SELECT version FROM specimen_container WHERE id=?",Long.class,cid),request.state(),jdbc.query("SELECT * FROM label_job WHERE container_id=? ORDER BY created_at DESC,id DESC LIMIT 100",mapper,cid));
    }
    @Transactional(timeout=10)
    public View view(UUID id) {
        var job=row(id); authorize(job.containerId(),Permission.PRINT);
        var events=jdbc.query("SELECT * FROM label_job_event WHERE job_id=? ORDER BY job_version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("job_version"),r.getString("action"),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
        return new View(job,events);
    }
    @Transactional(timeout=10)
    public Checked verify(UUID id,Verify input) {
        validate(input); var job=view(id).job();
        if(!LabelBarcode.valid(input.barcode())||!job.containerId().equals(input.containerId())||!job.barcode().equals(input.barcode())) throw conflict("LABEL_IDENTITY_MISMATCH");
        return new Checked(true);
    }
    private void validate(Object input) { var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors); }
    public IdempotentCommands.Result create(UUID cid,Create input,String key) { return execute(cid,null,input,key,"CREATE"); }
    public IdempotentCommands.Result change(UUID id,Change input,String key,String action) {
        if(!List.of("REPRINT","FAIL","RETRY","CANCEL").contains(action)) throw new IllegalArgumentException("Unknown label action");
        return execute(row(id).containerId(),id,input,key,action);
    }
    private IdempotentCommands.Result execute(UUID cid,UUID jobId,Object input,String key,String action) {
        validate(input); var permission=action.equals("REPRINT")?Permission.REPRINT:Permission.PRINT;
        var scope=authorize(cid,permission); var rid=requestId(cid);
        return commands.execute(scope.hospitalId(),"LABEL_"+action+"_V1",key,Map.of("target",jobId==null?cid:jobId,"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { LabelService.this.authorize(cid,permission); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { LabelService.this.authorize(row(receipt.resourceId()).containerId(),permission); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid);
                jdbc.queryForList("SELECT id FROM specimen_container WHERE id=? FOR UPDATE",cid);
                LabelService.this.authorize(cid,permission);
                var request=requests.detail(rid);
                if(!request.state().equals("RECEIVED")) throw conflict("LABEL_REQUIRES_RECEIVED");
                long cv=jdbc.queryForObject("SELECT version FROM specimen_container WHERE id=?",Long.class,cid);
                UUID resultId; long version; Long previous=null;
                if(action.equals("CREATE")) {
                    var create=(Create)input;
                    if(request.version()!=create.requestVersion()||cv!=create.containerVersion()) throw conflict("VERSION_CONFLICT");
                    if(jdbc.queryForObject("SELECT count(*) FROM label_job WHERE container_id=? AND parent_job_id IS NULL",Long.class,cid)>0) throw conflict("LABEL_USE_REPRINT");
                    var data=request.containers().stream().filter(c->c.id().equals(cid)).findFirst().orElseThrow(LabelService::missing);
                    var numbers=jdbc.queryForList("SELECT p.case_number FROM specimen_container c JOIN pathology_case p ON p.id=c.case_id WHERE c.id=? AND c.received_at IS NOT NULL",String.class,cid);
                    if(numbers.size()!=1||numbers.getFirst()==null) throw conflict("LABEL_REQUIRES_RECEIVED");
                    String barcode=LabelBarcode.create(UUID.randomUUID()); resultId=UUID.randomUUID(); version=0;
                    jdbc.update("INSERT INTO label_identity(container_id,hospital_id,request_id,barcode) VALUES(?,?,?,?)",cid,scope.hospitalId(),rid,barcode);
                    jdbc.update("""
                        INSERT INTO label_job(id,hospital_id,request_id,container_id,barcode,template_version,state,request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,reason,created_by)
                        VALUES(?,?,?,?,?,'SYN-CONTAINER-1','PREVIEW_READY',?,?,?,?,?,?,?,?,?,'Initial synthetic label preview',?)
                        """,resultId,scope.hospitalId(),rid,cid,barcode,request.version(),cv,request.patientId(),request.patientLabel()==null?"Unknown synthetic patient":request.patientLabel(),request.encounterNumber(),request.requestNumber(),numbers.getFirst(),data.site(),data.laterality(),actor.id());
                    event(resultId,0,"CREATE","Initial synthetic label preview",actor.id());
                } else {
                    jdbc.queryForList("SELECT id FROM label_job WHERE id=? FOR UPDATE",jobId);
                    var job=row(jobId); var change=(Change)input;
                    if(job.version()!=change.expectedVersion()) throw conflict("VERSION_CONFLICT");
                    if(action.equals("REPRINT")) {
                        resultId=UUID.randomUUID(); version=0;
                        if(jdbc.update("UPDATE label_job SET version=version+1 WHERE id=? AND version=?",jobId,job.version())!=1) throw conflict("VERSION_CONFLICT");
                        event(jobId,job.version()+1,"REPRINT",change.reason(),actor.id());
                        jdbc.update("""
                            INSERT INTO label_job(id,hospital_id,request_id,container_id,barcode,parent_job_id,template_version,state,request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,reason,created_by)
                            SELECT ?,hospital_id,request_id,container_id,barcode,id,template_version,'PREVIEW_READY',request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,?,? FROM label_job WHERE id=?
                            """,resultId,change.reason(),actor.id(),jobId);
                        event(resultId,0,"CREATE",change.reason(),actor.id());
                    } else {
                        String next;
                        if(action.equals("FAIL")&&job.state().equals("PREVIEW_READY")) next="FAILED";
                        else if(action.equals("RETRY")&&job.state().equals("FAILED")&&job.attempts()<1000) next="PREVIEW_READY";
                        else if(action.equals("CANCEL")&&!job.state().equals("CANCELLED")) next="CANCELLED";
                        else throw conflict("LABEL_STATE_CONFLICT");
                        resultId=jobId; previous=job.version(); version=job.version()+1;
                        if(jdbc.update("UPDATE label_job SET state=?,version=version+1,attempts=attempts+? WHERE id=? AND version=?",next,action.equals("RETRY")?1:0,jobId,job.version())!=1) throw conflict("VERSION_CONFLICT");
                        event(jobId,version,action,change.reason(),actor.id());
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
