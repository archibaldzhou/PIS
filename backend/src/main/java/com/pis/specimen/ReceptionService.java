package com.pis.specimen;

import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.specimen.ReceptionContracts.*;

@Service
public class ReceptionService {
    public record ReceivedSource(UUID hospitalId,UUID caseId,String caseNumber) { }
    @Transactional(timeout=10)
    public ReceivedSource receivedSource(UUID rid) {
        var q=requests.detail(rid);
        if(!q.state().equals("RECEIVED")) throw new ApiException(HttpStatus.CONFLICT,"MATERIAL_SOURCE_NOT_READY","Received material is required");
        var rows=jdbc.query("SELECT p.hospital_id,p.id,p.case_number FROM specimen_reception s JOIN pathology_case p ON p.id=s.case_id WHERE s.request_id=?",(r,i)->new ReceivedSource(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),rid);
        if(rows.size()!=1) throw new ApiException(HttpStatus.CONFLICT,"MATERIAL_SOURCE_NOT_READY","Received material is required"); return rows.getFirst();
    }
    private final JdbcTemplate jdbc;
    private final RequestService requests;
    private final WorkflowAccess access;
    private final IdempotentCommands commands;
    private final Validator validator;
    public ReceptionService(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access,IdempotentCommands commands,Validator validator) {
        this.jdbc=jdbc; this.requests=requests; this.access=access; this.commands=commands; this.validator=validator;
    }
    @Transactional(timeout=10)
    public View view(UUID id) {
        var detail=requests.detail(id);
        var numbers=jdbc.queryForList("SELECT c.case_number FROM specimen_reception s JOIN pathology_case c ON c.id=s.case_id WHERE s.request_id=?",String.class,id);
        var events=jdbc.query("SELECT * FROM reception_event WHERE request_id=? ORDER BY request_version DESC LIMIT 100",
            (r,i)->new Event(r.getObject("id",UUID.class),r.getLong("request_version"),r.getString("action"),r.getString("category"),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
        return new View(detail,numbers.isEmpty()?null:numbers.getFirst(),events);
    }
    public IdempotentCommands.Result receive(UUID id,Check input,String key) { return change(id,input.expectedVersion(),input,key,"RECEIVE",Permission.RECEIVE); }
    public IdempotentCommands.Result exception(UUID id,ExceptionInput input,String key) { return change(id,input.expectedVersion(),input,key,"EXCEPTION",Permission.EXCEPTION); }
    public IdempotentCommands.Result resolve(UUID id,Decision input,String key) { return change(id,input.expectedVersion(),input,key,"RESOLVE",Permission.EXCEPTION); }
    public IdempotentCommands.Result sendBack(UUID id,Decision input,String key) { return change(id,input.expectedVersion(),input,key,"RETURN",Permission.EXCEPTION); }
    private IdempotentCommands.Result change(UUID id,Long expected,Object input,String key,String action,Permission permission) {
        var violations=validator.validate(input);
        if(!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
        var initial=requests.detail(id);
        var scope=access.require(initial.scopeId(),permission);
        return commands.execute(scope.hospitalId(),"RECEPTION_"+action+"_V1",key,Map.of("id",id,"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { access.require(scope.id(),permission); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { requests.detail(receipt.resourceId()); access.require(scope.id(),permission); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                // Restoring a submission shares T08's encounter-first duplicate-check protocol.
                if(action.equals("RESOLVE")) jdbc.queryForList("SELECT e.id FROM encounter e JOIN pathology_request r ON r.encounter_id=e.id WHERE r.id=? FOR UPDATE OF e",id);
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",id);
                access.require(scope.id(),permission);
                var current=requests.detail(id);
                if(current.version()!=expected) throw conflict("VERSION_CONFLICT");
                String required=(action.equals("RESOLVE")||action.equals("RETURN"))?"EXCEPTION":"SUBMITTED";
                if(!current.state().equals(required)) throw conflict("RECEPTION_STATE_CONFLICT");
                String next, eventAction=action, category=null, reason;
                if(action.equals("RECEIVE")) {
                    var check=(Check)input;
                    var planned=current.containers().stream().map(c->c.id()).collect(java.util.stream.Collectors.toSet());
                    if(!current.patientId().equals(check.patientId())||!current.encounterNumber().equals(check.encounterNumber())) {
                        category="IDENTITY"; reason="Identity check mismatch; reception blocked";
                    } else if(check.containerIds().size()!=planned.size()||!new HashSet<>(check.containerIds()).equals(planned)) {
                        category="QUANTITY"; reason="Container set mismatch; reception blocked";
                    } else reason="Identity and container set checked";
                    if(category!=null) { next="EXCEPTION"; eventAction="EXCEPTION"; }
                    else {
                        if(jdbc.queryForObject("SELECT count(*) FROM pathology_case WHERE request_id=?",Long.class,id)>0) throw conflict("RECEPTION_ALREADY_LINKED");
                        jdbc.queryForList("SELECT id FROM specimen_container WHERE request_id=? ORDER BY id FOR UPDATE",id);
                        if(jdbc.queryForObject("SELECT count(*) FROM specimen_container WHERE request_id=? AND (case_id IS NOT NULL OR received_at IS NOT NULL)",Long.class,id)>0) throw conflict("RECEPTION_ALREADY_LINKED");
                        UUID cid=UUID.randomUUID();
                        jdbc.update("INSERT INTO pathology_case(id,hospital_id,request_id,number_namespace,case_number) VALUES(?,?,?,'DEV-RECEPTION',?)",cid,scope.hospitalId(),id,"DEV-P-"+cid);
                        jdbc.update("INSERT INTO case_access_scope(hospital_id,case_id,campus_id,owning_department_id) VALUES(?,?,?,?)",scope.hospitalId(),cid,scope.campusId(),scope.departmentId());
                        jdbc.update("INSERT INTO specimen_reception(request_id,hospital_id,case_id,received_by) VALUES(?,?,?,?)",id,scope.hospitalId(),cid,actor.id());
                        int linked=jdbc.update("UPDATE specimen_container SET case_id=?,received_at=statement_timestamp(),version=version+1,updated_at=statement_timestamp() WHERE request_id=? AND case_id IS NULL AND received_at IS NULL",cid,id);
                        if(linked!=planned.size()) throw conflict("RECEPTION_CONTAINER_CONFLICT");
                        next="RECEIVED";
                    }
                } else if(action.equals("EXCEPTION")) {
                    var exception=(ExceptionInput)input; category=exception.category().name(); reason=exception.reason(); next="EXCEPTION";
                } else {
                    reason=((Decision)input).reason();
                    if(action.equals("RESOLVE")) {
                        var last=jdbc.queryForObject("SELECT category FROM reception_event WHERE request_id=? AND action='EXCEPTION' ORDER BY request_version DESC LIMIT 1",String.class,id);
                        if(!"INFORMATION".equals(last)) throw conflict("IDENTITY_OR_QUANTITY_REVIEW_REQUIRED");
                        if(jdbc.queryForObject("SELECT count(*) FROM pathology_request r JOIN request_workflow w ON w.request_id=r.id WHERE r.encounter_id=? AND r.id<>? AND w.scope_id=? AND w.state='SUBMITTED'",Long.class,current.encounterId(),id,scope.id())>0) throw conflict("DUPLICATE_REVIEW_REQUIRED");
                        next="SUBMITTED";
                    } else next="RETURNED";
                }
                jdbc.update("UPDATE request_workflow SET state=? WHERE request_id=?",next,id);
                if(jdbc.update("UPDATE pathology_request SET version=version+1,updated_at=statement_timestamp() WHERE id=? AND version=?",id,expected)!=1) throw conflict("VERSION_CONFLICT");
                jdbc.update("INSERT INTO reception_event(request_id,request_version,action,category,reason,actor_id) VALUES(?,?,?,?,?,?)",id,expected+1,eventAction,category,reason,actor.id());
                return new IdempotentCommands.Mutation(new CommandReceipt(200,"PATHOLOGY_REQUEST",id,expected+1),expected);
            }
        });
    }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Reception requires refresh or review"); }
}
