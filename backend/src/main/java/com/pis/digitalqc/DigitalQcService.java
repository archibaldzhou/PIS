package com.pis.digitalqc;

import static com.pis.digitalqc.DigitalQcContracts.*;
import com.pis.accession.WorkflowAccess;
import com.pis.api.ApiException;
import com.pis.audit.*;
import com.pis.idempotency.*;
import com.pis.scan.ScanService;
import com.pis.scan.ScanContracts.Job;
import com.pis.storage.*;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DigitalQcService {
    private final JdbcTemplate jdbc;
    private final WorkflowAccess access;
    private final ScanService scans;
    private final StorageService storage;
    private final IdempotentCommands commands;
    private final AuditRecorder audit;
    private final Validator validator;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    public DigitalQcService(JdbcTemplate jdbc,WorkflowAccess access,ScanService scans,StorageService storage,
            IdempotentCommands commands,AuditRecorder audit,Validator validator,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.access=access;this.scans=scans;this.storage=storage;
        this.commands=commands;this.audit=audit;this.validator=validator;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    private record Context(Job job,UUID hospital,UUID scope,UUID actor) {}
    private record Head(long version,Long assessmentVersion,String state) {}
    private Context context(UUID request,UUID scan) {
        try {
            var actor=access.actor();
            // Same first lock as T29 and material QC; retained through the command's commit.
            if(TransactionSynchronizationManager.isActualTransactionActive())
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",request);
            var job=scans.detail(request,scan);
            var scope=jdbc.queryForObject("SELECT scope_id FROM request_workflow WHERE request_id=?",UUID.class,request);
            var permitted=access.require(scope,WorkflowAccess.Permission.QC);
            lockGrant(actor.id(),scope);
            if(!qualified(actor.id(),scope))throw missing();
            storage.detail(request,job.objectId());
            return new Context(job,permitted.hospitalId(),scope,actor.id());
        } catch(org.springframework.security.access.AccessDeniedException e) {throw missing();}
    }
    private void lockGrant(UUID actor,UUID scope) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            { jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",actor);
            jdbc.queryForList("SELECT user_id FROM workflow_grant WHERE user_id=? AND scope_id=? FOR SHARE",actor,scope);
            jdbc.queryForList("SELECT user_id FROM digital_qc_grant WHERE user_id=? AND scope_id=? FOR SHARE",actor,scope); }
    }
    private boolean qualified(UUID actor,UUID scope) {
        return jdbc.queryForObject("SELECT count(*) FROM digital_qc_grant g JOIN app_user u ON u.id=g.user_id WHERE g.user_id=? AND g.scope_id=? AND g.qualification='SYN-DIGITAL-QC-1' AND g.revoked_at IS NULL AND g.valid_until>statement_timestamp() AND u.synthetic_only AND u.enabled AND EXISTS(SELECT 1 FROM workflow_grant w WHERE w.user_id=g.user_id AND w.scope_id=g.scope_id AND w.can_read AND w.can_qc AND w.revoked_at IS NULL AND w.valid_from<=statement_timestamp() AND (w.valid_until IS NULL OR w.valid_until>statement_timestamp()))",Long.class,actor,scope)==1;
    }
    private void validate(Object value) {
        var errors=validator.validate(value);
        if(!errors.isEmpty())throw new jakarta.validation.ConstraintViolationException(errors);
    }
    private Head head(UUID scan) {
        var rows=jdbc.query("SELECT version,assessment_version,state FROM digital_qc_head WHERE scan_id=?",
            (r,i)->new Head(r.getLong(1),r.getObject(2,Long.class),r.getString(3)),scan);
        return rows.isEmpty()?new Head(-1,null,"UNASSESSED"):rows.getFirst();
    }
    private Assessment assessment(UUID scan,Long version) {
        if(version==null)return null;
        return jdbc.queryForObject("SELECT * FROM digital_qc_assessment WHERE scan_id=? AND version=?",assessmentMapper(),scan,version);
    }
    private org.springframework.jdbc.core.RowMapper<Assessment> assessmentMapper() { return (r,i)->
            new Assessment(r.getLong("version"),r.getLong("scan_version"),r.getObject("object_id",UUID.class),r.getString("object_hash"),
                r.getObject("slide_id",UUID.class),r.getString("source_basis"),r.getString("checklist"),r.getString("coverage"),r.getString("focus"),
                r.getString("missing"),r.getInt("coverage_percent"),r.getInt("missing_tiles"),List.of(json.readValue(r.getString("regions"),Region[].class)),
                r.getString("note"),r.getBoolean("passed"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant());
    }
    private boolean current(Job job) {
        return jdbc.queryForObject("SELECT head FROM scan_series WHERE slide_id=?",Long.class,job.slideId())==job.ordinal();
    }
    private String sourceInvalid(Context c) {
        var j=c.job();
        if(!current(j))return "RESCANNED";
        if(!j.state().equals("PENDING_DIGITAL_QC"))return "SCAN_ISOLATED";
        if(!j.invalidReason().isEmpty())return "SOURCE_CHANGED";
        if(!j.caseId().equals(j.claimedCaseId())||!j.patientId().equals(j.claimedPatientId()))return "IDENTITY_MISMATCH";
        return "";
    }
    private String invalid(Context c,Assessment a) {
        String invalid=sourceInvalid(c);if(!invalid.isEmpty())return invalid;
        if(a==null)return "UNASSESSED";
        var j=c.job();
        if(a.scanVersion()!=j.version()||!a.objectId().equals(j.objectId())||!a.objectHash().equals(j.objectHash())
            ||!a.slideId().equals(j.slideId())||!a.sourceBasis().equals(j.sourceBasis()))return "BINDING_CHANGED";
        lockGrant(a.actorId(),c.scope());
        if(!qualified(a.actorId(),c.scope()))return "ASSESSOR_REVOKED";
        if(!a.passed())return "QC_NOT_PASSED";
        return "";
    }
    private String publicationInvalid(Context c,Head h) {
        String reason=invalid(c,assessment(c.job().id(),h.assessmentVersion()));
        if(!reason.isEmpty())return reason;
        if(h.state().equals("REVOKED"))return "QC_REVOKED";
        if(h.state().equals("PUBLISHED")) {
            var publisher=jdbc.queryForObject("SELECT actor_id FROM digital_qc_event WHERE scan_id=? AND version=? AND action='PUBLISH'",UUID.class,c.job().id(),h.version());
            lockGrant(publisher,c.scope());if(!qualified(publisher,c.scope()))return "PUBLISHER_REVOKED";
        }
        return "";
    }
    private List<Event> events(UUID scan) {
        return jdbc.query("SELECT * FROM digital_qc_event WHERE scan_id=? ORDER BY version DESC LIMIT 100",(r,i)->
            new Event(r.getLong("version"),r.getString("action"),r.getLong("assessment_version"),r.getObject("actor_id",UUID.class),
                r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),scan);
    }
    public View view(UUID request,UUID scan) {
        return tx.execute(s->{var c=context(request,scan);var h=head(scan);var a=assessment(scan,h.assessmentVersion());String why=publicationInvalid(c,h);
            String effective=!why.isEmpty()?"ISOLATED":h.state().equals("PUBLISHED")?"PUBLISHED_SYNTHETIC_CONTRACT":"EVALUATED_NOT_PUBLISHED";
            audit.append(c.hospital(),"DIGITAL_QC_READ_V1","SCAN_IMPORT",scan,null,Math.max(0,h.version()));
            var j=c.job();return new View(request,scan,j.caseId(),j.slideId(),j.objectId(),j.objectHash(),j.version(),j.ordinal(),current(j),h.version(),h.state(),effective,why,a,events(scan),"SYNTHETIC_CONTRACT_ONLY_NO_VIEWER");});
    }
    public List<Assessment> history(UUID request,UUID scan) {
        return tx.execute(s->{var c=context(request,scan);
            var result=jdbc.query("SELECT * FROM digital_qc_assessment WHERE scan_id=? ORDER BY version DESC LIMIT 100",assessmentMapper(),scan);
            audit.append(c.hospital(),"DIGITAL_QC_HISTORY_V1","SCAN_IMPORT",scan,null,Math.max(0,head(scan).version()));return List.copyOf(result);});
    }
    public IdempotentCommands.Result evaluate(UUID request,UUID scan,Evaluation input,String key) {
        validate(input);var initial=context(request,scan);
        return commands.execute(initial.hospital(),"DIGITAL_QC_EVALUATE_V1",key,Map.of("request",request,"scan",scan,"input",input),new IdempotentCommands.Work(){
            public void authorize(CurrentActor.Actor actor){context(request,scan);}
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);}
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
                var c=context(request,scan);var j=c.job();var h=head(scan);
                if(h.version()!=input.expectedVersion())throw conflict("VERSION_CONFLICT");
                // Reserve final versions for explicit publication and revocation.
                if(h.version()>=97)throw conflict("DIGITAL_QC_LIMIT");
                if(!sourceInvalid(c).isEmpty())throw conflict("DIGITAL_QC_SOURCE_CHANGED");
                if(input.scanVersion()!=j.version()||!input.objectId().equals(j.objectId())||!input.slideId().equals(j.slideId())||!input.objectHash().equals(j.objectHash()))throw conflict("DIGITAL_QC_BINDING");
                if(j.width()==null||j.height()==null||!DigitalQcPolicy.regionsFit(input,j.width(),j.height()))throw problem(HttpStatus.BAD_REQUEST,"DIGITAL_QC_REGION");
                long next=h.version()+1;
                jdbc.update("INSERT INTO digital_qc_head(scan_id) VALUES(?) ON CONFLICT DO NOTHING",scan);
                jdbc.update("INSERT INTO digital_qc_assessment(scan_id,version,scan_version,object_id,object_hash,slide_id,source_basis,checklist,coverage,focus,missing,coverage_percent,missing_tiles,regions,note,passed,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
                    scan,next,j.version(),j.objectId(),j.objectHash(),j.slideId(),j.sourceBasis(),input.checklist(),input.coverage(),input.focus(),input.missing(),input.coveragePercent(),input.missingTiles(),json.writeValueAsString(input.regions()),input.note(),DigitalQcPolicy.passed(input),c.actor());
                if(jdbc.update("UPDATE digital_qc_head SET version=?,assessment_version=?,state='EVALUATED' WHERE scan_id=? AND version=?",next,next,scan,h.version())!=1)throw conflict("VERSION_CONFLICT");
                event(c,next,"EVALUATE",next,input.note());return mutation(scan,h.version(),next);
            }
        });
    }
    private void event(Context c,long version,String action,long assessmentVersion,String reason) {
        jdbc.update("INSERT INTO digital_qc_event(scan_id,version,action,assessment_version,actor_id,reason) VALUES(?,?,?,?,?,?)",c.job().id(),version,action,assessmentVersion,c.actor(),reason);
    }
    private static IdempotentCommands.Mutation mutation(UUID id,long old,long next) {
        return new IdempotentCommands.Mutation(new CommandReceipt(200,"DIGITAL_QC",id,next),old<0?null:old);
    }
    public IdempotentCommands.Result command(UUID request,UUID scan,String action,Command input,String key) {
        validate(input);if(!Set.of("PUBLISH","REVOKE").contains(action))throw problem(HttpStatus.BAD_REQUEST,"DIGITAL_QC_ACTION");
        var initial=context(request,scan);
        return commands.execute(initial.hospital(),"DIGITAL_QC_"+action+"_V1",key,Map.of("request",request,"scan",scan,"input",input),new IdempotentCommands.Work(){
            public void authorize(CurrentActor.Actor actor){context(request,scan);}
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt){authorize(actor);}
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor){
                var c=context(request,scan);var h=head(scan);
                if(h.version()!=input.expectedVersion()||!Objects.equals(h.assessmentVersion(),input.assessmentVersion()))throw conflict("VERSION_CONFLICT");
                if(h.version()>=99 || (action.equals("PUBLISH") && h.version()>=98))throw conflict("DIGITAL_QC_LIMIT");
                if(action.equals("PUBLISH")){if(!h.state().equals("EVALUATED")||!invalid(c,assessment(scan,h.assessmentVersion())).isEmpty())throw conflict("DIGITAL_QC_NOT_READY");}
                else if(!Set.of("PUBLISHED","EVALUATED").contains(h.state()))throw conflict("DIGITAL_QC_STATE");
                long next=h.version()+1;String state=action.equals("PUBLISH")?"PUBLISHED":"REVOKED";
                if(jdbc.update("UPDATE digital_qc_head SET version=?,state=? WHERE scan_id=? AND version=?",next,state,scan,h.version())!=1)throw conflict("VERSION_CONFLICT");
                event(c,next,action,h.assessmentVersion(),input.reason());return mutation(scan,h.version(),next);
            }
        });
    }
    private Context consumable(UUID request,UUID scan,long publicationVersion) {
        var c=context(request,scan);var h=head(scan);
        if(h.version()!=publicationVersion||!h.state().equals("PUBLISHED")||!publicationInvalid(c,h).isEmpty())throw conflict("DIGITAL_QC_NOT_READY");
        return c;
    }
    public StorageProvider.Slice consume(UUID request,UUID scan,long publicationVersion,String range) {
        var before=tx.execute(s->{var c=consumable(request,scan,publicationVersion);audit.append(c.hospital(),"DIGITAL_QC_CONSUME_ATTEMPT_V1","SCAN_IMPORT",scan,null,publicationVersion);return c.job();});
        var bytes=storage.bytes(request,before.objectId(),range,"DOWNLOAD");
        tx.executeWithoutResult(s->{var c=consumable(request,scan,publicationVersion);
            if(!c.job().objectId().equals(before.objectId())||!c.job().objectHash().equals(before.objectHash()))throw conflict("DIGITAL_QC_BINDING");
            audit.append(c.hospital(),"DIGITAL_QC_CONSUME_V1","SCAN_IMPORT",scan,null,publicationVersion);});
        return bytes;
    }
    private static ApiException missing(){return problem(HttpStatus.NOT_FOUND,"DIGITAL_QC_NOT_FOUND");}
    private static ApiException conflict(String code){return problem(HttpStatus.CONFLICT,code);}
    private static ApiException problem(HttpStatus status,String code){return new ApiException(status,code,"Synthetic digital QC unavailable; verify authorized exact version");}
}
