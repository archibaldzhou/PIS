package com.pis.accession;

import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.accession.RequestContracts.*;

@Service
public class RequestService {
    private final JdbcTemplate jdbc;
    private final WorkflowAccess access;
    private final IdempotentCommands commands;
    private final Validator validator;
    private final com.pis.core.ManualIdentityRegistration identities;
    public RequestService(JdbcTemplate jdbc, WorkflowAccess access, IdempotentCommands commands, Validator validator,
            com.pis.core.ManualIdentityRegistration identities) {
        this.jdbc = jdbc; this.access = access; this.commands = commands; this.validator = validator;
        this.identities = identities;
    }
    @Transactional(timeout=10)
    public List<WorkflowAccess.Scope> scopes() {
        var actor = access.actor();
        var ids = jdbc.query("""
            SELECT s.id FROM workflow_scope s JOIN workflow_grant g ON g.scope_id=s.id
            WHERE g.user_id=? AND s.enabled AND g.can_read AND g.revoked_at IS NULL
              AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())
            ORDER BY s.id LIMIT 100
            """, (r,i) -> r.getObject(1, UUID.class), actor.id());
        return ids.stream().map(id -> access.require(id, false)).toList();
    }
    @Transactional(timeout=10)
    public List<Encounter> encounters(UUID scopeId, String number) {
        var scope = access.require(scopeId, false);
        return jdbc.query("""
            SELECT e.id, e.patient_id, p.display_name, e.encounter_number FROM encounter e
            JOIN patient p ON p.id=e.patient_id AND p.hospital_id=e.hospital_id
            WHERE e.hospital_id=? AND e.department_id=? AND e.encounter_number=? ORDER BY e.id LIMIT 20
            """, (r,i) -> new Encounter(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4)),
            scope.hospitalId(),scope.departmentId(),number);
    }
    private static final String DETAIL = """
        SELECT r.*, w.scope_id, w.state, w.clinical_history, w.sampled_at, p.display_name,
            e.encounter_number, d.name AS department_name FROM pathology_request r
        JOIN request_workflow w ON w.request_id=r.id JOIN patient p ON p.id=r.patient_id
        JOIN encounter e ON e.id=r.encounter_id JOIN department d ON d.id=r.requesting_department_id
        """;
    private final RowMapper<Detail> mapper = (r,i) -> new Detail(r.getObject("id",UUID.class),r.getObject("scope_id",UUID.class),
        r.getLong("version"),r.getString("request_number"),r.getObject("patient_id",UUID.class),r.getString("display_name"),
        r.getObject("encounter_id",UUID.class),r.getString("encounter_number"),r.getString("department_name"),
        instant(r.getObject("requested_at",OffsetDateTime.class)),r.getString("state"),r.getString("clinical_history"),
        instant(r.getObject("sampled_at",OffsetDateTime.class)),List.of());
    private static Instant instant(OffsetDateTime value) { return value==null?null:value.toInstant(); }
    private static Object timestamp(Instant value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
    /** Minimal public cross-domain scope contract; does not expose patient or draft content. */
    @Transactional(timeout=10)
    public WorkflowAccess.Scope authorizedScope(UUID requestId) { return requireResource(requestId,false); }

    public record CaseIdentity(UUID hospitalId,UUID scopeId,UUID sourceId,UUID requestId,UUID caseId,UUID patientId){}
    @Transactional(timeout=10) public CaseIdentity caseIdentity(UUID id){var scope=requireResource(id,false);var rows=jdbc.query("SELECT c.id,r.patient_id FROM pathology_case c JOIN pathology_request r ON r.id=c.request_id WHERE r.id=?",(r,i)->new CaseIdentity(scope.hospitalId(),scope.id(),scope.sourceId(),id,r.getObject(1,UUID.class),r.getObject(2,UUID.class)),id);if(rows.size()!=1)throw new ApiException(HttpStatus.NOT_FOUND,"ADAPTER_NOT_FOUND","Synthetic adapter resource unavailable");return rows.getFirst();}

    @Transactional(timeout=10)
    public Detail detail(UUID id) {
        requireResource(id,false);
        var row=jdbc.query(DETAIL+" WHERE r.id=?",mapper,id).getFirst();
        var containers=jdbc.query("""
            SELECT c.id,d.* FROM specimen_container c JOIN request_container_detail d ON d.container_id=c.id
            WHERE c.request_id=? ORDER BY c.created_at,c.id
            """,(r,i)->new Container(r.getObject("id",UUID.class),r.getString("site"),r.getString("laterality"),
                r.getInt("material_quantity"),r.getString("fixative"),instant(r.getObject("fixed_at",OffsetDateTime.class))),id);
        return new Detail(row.id(),row.scopeId(),row.version(),row.requestNumber(),row.patientId(),row.patientLabel(),
            row.encounterId(),row.encounterNumber(),row.department(),row.requestedAt(),row.state(),row.clinicalHistory(),row.sampledAt(),containers);
    }
    @Transactional(timeout=10)
    public Page list(UUID scopeId,String keyword,String state,LocalDate date,int page) {
        access.require(scopeId,false);
        if(page<1||page>10000 || keyword.length()>255 || !List.of("","DRAFT","SUBMITTED","RECEIVED","EXCEPTION","RETURNED").contains(state)) throw bad("INVALID_QUERY");
        String where=" WHERE w.scope_id=? AND (?='' OR r.request_number=? OR e.encounter_number=? OR r.patient_id::text=?) AND (?='' OR w.state=?)"
            +" AND (? OR (r.created_at>=? AND r.created_at<?))";
        Object start=date==null?null:date.atStartOfDay().atOffset(ZoneOffset.UTC);
        Object end=date==null?null:date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        Object[] args={scopeId,keyword,keyword,keyword,keyword,state,state,date==null,start,end};
        long total=jdbc.queryForObject("SELECT count(*) FROM ("+DETAIL+where+") counted",Long.class,args);
        var parameters=new java.util.ArrayList<>(java.util.Arrays.asList(args)); parameters.add((page-1)*20);
        var items=jdbc.query(DETAIL+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT 20 OFFSET ?",mapper,parameters.toArray());
        return new Page(items,total,page,20);
    }
    private UUID scopeId(UUID id) {
        access.actor();
        var ids=jdbc.query("SELECT scope_id FROM request_workflow WHERE request_id=?",(r,i)->r.getObject(1,UUID.class),id);
        if(ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND","Request is not available");
        return ids.getFirst();
    }
    private WorkflowAccess.Scope requireResource(UUID id,boolean write) {
        try { return access.require(scopeId(id),write); }
        catch (org.springframework.security.access.AccessDeniedException denied) {
            throw new ApiException(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND","Request is not available");
        }
    }
    private void validate(Object value) {
        var violations=validator.validate(value);
        if(!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
    }
    public IdempotentCommands.Result create(Create input,String key) {
        validate(input);
        var scope=access.require(input.scopeId(),true);
        return commands.execute(scope.hospitalId(),"REQUEST_CREATE_V1",key,input,new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { access.require(scope.id(),true); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { requireResource(receipt.resourceId(),true); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                var patients=jdbc.query("SELECT patient_id FROM encounter WHERE id=? AND hospital_id=? AND department_id=? FOR SHARE",
                    (r,i)->r.getObject(1,UUID.class),input.encounterId(),scope.hospitalId(),scope.departmentId());
                if(patients.size()!=1) throw bad("ENCOUNTER_MISMATCH");
                access.require(scope.id(),true);
                UUID id=UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO pathology_request(id,hospital_id,patient_id,encounter_id,source_system_id,request_number,requesting_department_id,requested_at)
                    VALUES(?,?,?,?,?,?,?,statement_timestamp())
                    """,id,scope.hospitalId(),patients.getFirst(),input.encounterId(),scope.sourceId(),"DEV-AP-"+id,scope.departmentId());
                jdbc.update("INSERT INTO request_workflow(request_id,hospital_id,scope_id,state,clinical_history,sampled_at,created_by) VALUES(?,?,?,'DRAFT',?,?,?)",
                    id,scope.hospitalId(),scope.id(),input.draft().clinicalHistory(),timestamp(input.draft().sampledAt()),actor.id());
                for(var container:input.draft().containers()) {
                    UUID cid=UUID.randomUUID();
                    jdbc.update("INSERT INTO specimen_container(id,hospital_id,request_id) VALUES(?,?,?)",cid,scope.hospitalId(),id);
                    jdbc.update("INSERT INTO request_container_detail(container_id,site,laterality,material_quantity,fixative,fixed_at) VALUES(?,?,?,?,?,?)",
                        cid,container.site(),container.laterality().name(),container.materialQuantity(),container.fixative(),timestamp(container.fixedAt()));
                }
                return new IdempotentCommands.Mutation(new CommandReceipt(201,"PATHOLOGY_REQUEST",id,0),null);
            }
        });
    }
    public IdempotentCommands.Result createManual(ManualCreate input,String key) {
        validate(input);
        var scope=access.require(input.scopeId(),true);
        return commands.execute(scope.hospitalId(),"REQUEST_MANUAL_CREATE_V1",key,input,new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { access.require(scope.id(),true); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { requireResource(receipt.resourceId(),true); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                // Scope/grant/account SHARE locks coordinate revocation throughout the entire write.
                access.require(scope.id(),true);
                var identity=identities.register(scope.hospitalId(),scope.departmentId(),scope.sourceId(),input.patientName(),input.encounterNumber());
                UUID id=UUID.randomUUID();
                jdbc.update("INSERT INTO pathology_request(id,hospital_id,patient_id,encounter_id,source_system_id,request_number,requesting_department_id,requested_at) VALUES(?,?,?,?,?,?,?,statement_timestamp())",
                    id,scope.hospitalId(),identity.patientId(),identity.encounterId(),scope.sourceId(),"DEV-AP-"+id,scope.departmentId());
                jdbc.update("INSERT INTO request_workflow(request_id,hospital_id,scope_id,state,clinical_history,sampled_at,created_by) VALUES(?,?,?,'DRAFT',?,?,?)",
                    id,scope.hospitalId(),scope.id(),input.draft().clinicalHistory(),timestamp(input.draft().sampledAt()),actor.id());
                for(var container:input.draft().containers()) {
                    UUID cid=UUID.randomUUID();
                    jdbc.update("INSERT INTO specimen_container(id,hospital_id,request_id) VALUES(?,?,?)",cid,scope.hospitalId(),id);
                    jdbc.update("INSERT INTO request_container_detail(container_id,site,laterality,material_quantity,fixative,fixed_at) VALUES(?,?,?,?,?,?)",
                        cid,container.site(),container.laterality().name(),container.materialQuantity(),container.fixative(),timestamp(container.fixedAt()));
                }
                return new IdempotentCommands.Mutation(new CommandReceipt(201,"PATHOLOGY_REQUEST",id,0),null);
            }
        });
    }
    public IdempotentCommands.Result edit(UUID id,Edit input,String key) { validate(input); return change(id,input.expectedVersion(),input,key,false); }
    public IdempotentCommands.Result submit(UUID id,Submit input,String key) { validate(input); return change(id,input.expectedVersion(),input,key,true); }
    private IdempotentCommands.Result change(UUID id,long version,Object input,String key,boolean submit) {
        var scope=requireResource(id,true);
        return commands.execute(scope.hospitalId(),submit?"REQUEST_SUBMIT_V1":"REQUEST_EDIT_V1",key,Map.of("id",id,"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { access.require(scope.id(),true); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { requireResource(receipt.resourceId(),true); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                // Serialize submissions on the encounter before taking an individual request lock.
                if(submit) jdbc.queryForList("SELECT e.id FROM encounter e JOIN pathology_request r ON r.encounter_id=e.id WHERE r.id=? FOR UPDATE OF e",id);
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",id);
                access.require(scope.id(),true);
                var current=detail(id);
                if(current.version()!=version) throw conflict("VERSION_CONFLICT");
                if(!current.state().equals("DRAFT")) throw conflict("REQUEST_NOT_DRAFT");
                if(submit) {
                    if(current.clinicalHistory().isBlank()||current.sampledAt()==null||current.containers().isEmpty()
                        ||current.containers().stream().anyMatch(c->c.fixative().isBlank()||c.fixedAt()==null||c.fixedAt().isBefore(current.sampledAt()))) throw conflict("REQUEST_INCOMPLETE");
                    if(jdbc.queryForObject("SELECT count(*) FROM pathology_request r JOIN request_workflow w ON w.request_id=r.id WHERE r.encounter_id=? AND r.id<>? AND w.scope_id=? AND w.state='SUBMITTED'",Long.class,current.encounterId(),id,scope.id())>0) throw conflict("DUPLICATE_REVIEW_REQUIRED");
                    jdbc.update("UPDATE request_workflow SET state='SUBMITTED',submitted_by=?,submitted_at=statement_timestamp() WHERE request_id=?",actor.id(),id);
                } else {
                    var draft=((Edit)input).draft();
                    if(draft.containers().size()!=current.containers().size()) throw conflict("CONTAINER_SET_IMMUTABLE");
                    jdbc.update("UPDATE request_workflow SET clinical_history=?,sampled_at=? WHERE request_id=?",draft.clinicalHistory(),timestamp(draft.sampledAt()),id);
                    for(int i=0;i<draft.containers().size();i++) {
                        var c=draft.containers().get(i);
                        jdbc.update("UPDATE request_container_detail SET site=?,laterality=?,material_quantity=?,fixative=?,fixed_at=? WHERE container_id=?",
                            c.site(),c.laterality().name(),c.materialQuantity(),c.fixative(),timestamp(c.fixedAt()),current.containers().get(i).id());
                    }
                }
                if(jdbc.update("UPDATE pathology_request SET version=version+1,updated_at=statement_timestamp() WHERE id=? AND version=?",id,version)!=1) throw conflict("VERSION_CONFLICT");
                return new IdempotentCommands.Mutation(new CommandReceipt(200,"PATHOLOGY_REQUEST",id,version+1),version);
            }
        });
    }
    private static ApiException bad(String code) { return new ApiException(HttpStatus.BAD_REQUEST,code,"Invalid request input"); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Request requires refresh or review"); }
}
