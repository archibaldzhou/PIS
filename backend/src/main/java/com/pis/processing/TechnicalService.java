package com.pis.processing;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.grossing.GrossService;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.processing.TechnicalContracts.*;
@Service
public class TechnicalService {
    private final JdbcTemplate jdbc; private final RequestService requests; private final WorkflowAccess access;
    private final GrossService gross; private final IdempotentCommands commands; private final Validator validator;
    public TechnicalService(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access,GrossService gross,IdempotentCommands commands,Validator validator) {
        this.jdbc=jdbc; this.requests=requests; this.access=access; this.gross=gross; this.commands=commands; this.validator=validator;
    }
    private GrossService.Released context(UUID rid,boolean handoff) {
        var request=requests.detail(rid);
        try { access.require(request.scopeId(),handoff?Permission.HANDOFF:Permission.PROCESS); }
        catch(org.springframework.security.access.AccessDeniedException error) { throw missing(); }
        return gross.released(rid);
    }
    private UUID requestId(UUID id) {
        access.actor(); var rows=jdbc.queryForList("SELECT request_id FROM technical_task WHERE id=?",UUID.class,id);
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private static final RowMapper<Task> TASK=(r,i)->new Task(r.getObject("id",UUID.class),r.getObject("cassette_id",UUID.class),r.getString("kind"),r.getString("state"),r.getObject("owner_id",UUID.class),r.getObject("predecessor_id",UUID.class),r.getObject("rework_of",UUID.class),r.getLong("version"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class).toInstant());
    private Task task(UUID id) { return jdbc.queryForObject("SELECT * FROM technical_task WHERE id=?",TASK,id); }
    @Transactional(timeout=10)
    public View view(UUID rid) {
        context(rid,false); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);
        var source=context(rid,false);
        return new View(source,access.actor().id(),jdbc.query("SELECT * FROM technical_task WHERE request_id=? ORDER BY created_at,id LIMIT 50",TASK,rid));
    }
    @Transactional(timeout=10)
    public Detail detail(UUID id) {
        var rid=requestId(id); context(rid,false); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid); context(rid,false);
        var events=jdbc.query("SELECT * FROM technical_event WHERE task_id=? ORDER BY task_version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("task_version"),r.getString("action"),r.getObject("actor_id",UUID.class),r.getObject("previous_owner_id",UUID.class),r.getObject("next_owner_id",UUID.class),r.getObject("related_task_id",UUID.class),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id);
        return new Detail(task(id),events);
    }
    public IdempotentCommands.Result create(UUID rid,Create input,String key) { return command(rid,null,input,key,"CREATE"); }
    public IdempotentCommands.Result decide(UUID id,Decision input,String key,String action) {
        if(!List.of("CLAIM","OFFER","ACCEPT","WITHDRAW","FINISH_SIMULATION","ABORT","REWORK").contains(action)) throw new IllegalArgumentException("Unknown technical action");
        return command(requestId(id),id,input,key,action);
    }
    private void source(GrossService.Released release,UUID cassette) {
        if(release.cassettes().stream().noneMatch(c->c.id().equals(cassette))) throw conflict("TECH_SOURCE_MISMATCH");
    }
    private void predecessor(UUID rid,UUID cassette,UUID predecessor) {
        if(predecessor==null) return;
        if(jdbc.queryForObject("SELECT count(*) FROM technical_task WHERE id=? AND request_id=? AND cassette_id=? AND state='SIMULATED_DONE'",Long.class,predecessor,rid,cassette)!=1) throw conflict("TECH_PREDECESSOR_NOT_READY");
    }
    private void capacity(UUID rid) { if(jdbc.queryForObject("SELECT count(*) FROM technical_task WHERE request_id=?",Long.class,rid)>=50) throw conflict("TECH_LIMIT_REACHED"); }
    private void insert(GrossService.Released s,UUID id,UUID cassette,String kind,UUID predecessor,UUID rework,UUID actor) {
        jdbc.update("INSERT INTO technical_task(id,hospital_id,request_id,case_id,record_id,cassette_id,kind,state,predecessor_id,rework_of,created_by) VALUES(?,?,?,?,?,?,?,'QUEUED',?,?,?)",id,s.hospitalId(),s.requestId(),s.caseId(),s.recordId(),cassette,kind,predecessor,rework,actor);
    }
    private void event(UUID id,long version,String action,UUID actor,UUID previous,UUID next,UUID related,String reason) {
        jdbc.update("INSERT INTO technical_event(task_id,task_version,action,actor_id,previous_owner_id,next_owner_id,related_task_id,reason) VALUES(?,?,?,?,?,?,?,?)",id,version,action,actor,previous,next,related,reason);
    }
    private IdempotentCommands.Result command(UUID rid,UUID id,Object input,String key,String action) {
        var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors);
        boolean handoff=List.of("OFFER","ACCEPT","WITHDRAW").contains(action); var initial=context(rid,handoff);
        return commands.execute(initial.hospitalId(),"TECH_"+action+"_V1",key,Map.of("request",rid,"task",id==null?"":id.toString(),"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { context(rid,handoff); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { context(requestId(receipt.resourceId()),handoff); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid); var released=context(rid,handoff);
                if(action.equals("CREATE")) {
                    var c=(Create)input; if(released.requestVersion()!=c.requestVersion()) throw conflict("VERSION_CONFLICT");
                    source(released,c.cassetteId()); predecessor(rid,c.cassetteId(),c.predecessorId()); capacity(rid);
                    var result=UUID.randomUUID(); insert(released,result,c.cassetteId(),c.kind().name(),c.predecessorId(),null,actor.id());
                    event(result,0,"CREATE",actor.id(),null,null,c.predecessorId(),c.reason());
                    return new IdempotentCommands.Mutation(new CommandReceipt(201,"TECHNICAL_TASK",result,0),null);
                }
                jdbc.queryForList("SELECT id FROM technical_task WHERE id=? FOR UPDATE",id); var old=task(id); var d=(Decision)input;
                if(old.version()!=d.expectedVersion()) throw conflict("VERSION_CONFLICT");
                if(!old.cassetteId().equals(d.confirmedCassetteId())) throw conflict("TECH_SOURCE_MISMATCH");
                source(released,old.cassetteId()); predecessor(rid,old.cassetteId(),old.predecessorId());
                String state=old.state(); UUID owner=old.ownerId(); UUID result=id; long version=old.version()+1;
                switch(action) {
                    case "CLAIM" -> { requireState(state,"QUEUED"); state="ACTIVE"; owner=actor.id(); }
                    case "OFFER" -> { requireState(state,"ACTIVE"); requireOwner(owner,actor.id()); state="HANDOFF_PENDING"; }
                    case "ACCEPT" -> { requireState(state,"HANDOFF_PENDING"); if(Objects.equals(owner,actor.id())) throw conflict("TECH_SELF_HANDOFF"); state="ACTIVE"; owner=actor.id(); }
                    case "WITHDRAW" -> { requireState(state,"HANDOFF_PENDING"); requireOwner(owner,actor.id()); state="ACTIVE"; }
                    case "FINISH_SIMULATION" -> { requireState(state,"ACTIVE"); requireOwner(owner,actor.id()); state="SIMULATED_DONE"; }
                    case "ABORT" -> { if(!List.of("QUEUED","ACTIVE","HANDOFF_PENDING").contains(state)) throw conflict("TECH_STATE_CONFLICT"); if(!state.equals("QUEUED")) requireOwner(owner,actor.id()); state="ABORTED"; }
                    case "REWORK" -> {
                        if(!List.of("ABORTED","SIMULATED_DONE").contains(state)) throw conflict("TECH_STATE_CONFLICT");
                        if(jdbc.queryForObject("SELECT count(*) FROM technical_task WHERE rework_of=?",Long.class,id)>0) throw conflict("TECH_REWORK_EXISTS");
                        capacity(rid); result=UUID.randomUUID(); insert(released,result,old.cassetteId(),old.kind(),old.predecessorId(),id,actor.id());
                        event(result,0,"REWORK_CREATED",actor.id(),null,null,id,d.reason());
                    }
                    default -> throw new IllegalArgumentException("Unknown technical action");
                }
                if(jdbc.update("UPDATE technical_task SET state=?,owner_id=?,version=version+1 WHERE id=? AND version=?",state,owner,id,old.version())!=1) throw conflict("VERSION_CONFLICT");
                event(id,version,action,actor.id(),old.ownerId(),owner,result.equals(id)?null:result,d.reason());
                boolean created=!result.equals(id);
                return new IdempotentCommands.Mutation(new CommandReceipt(created?201:200,"TECHNICAL_TASK",result,created?0:version),created?null:old.version());
            }
        });
    }
    private static void requireState(String actual,String expected) { if(!actual.equals(expected)) throw conflict("TECH_STATE_CONFLICT"); }
    private static void requireOwner(UUID owner,UUID actor) { if(!Objects.equals(owner,actor)) throw new ApiException(HttpStatus.FORBIDDEN,"TECH_OWNER_REQUIRED","Current task ownership is required"); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"TECH_NOT_FOUND","Technical resource is not available"); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Technical task requires refresh or review"); }
}
