package com.pis.grossing;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Validator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.grossing.GrossContracts.*;

@Service
public class GrossService {
    private final JdbcTemplate jdbc; private final RequestService requests; private final WorkflowAccess access;
    private final IdempotentCommands commands; private final Validator validator; private final PhotoStore photos;
    public GrossService(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access,IdempotentCommands commands,Validator validator,PhotoStore photos) {
        this.jdbc=jdbc; this.requests=requests; this.access=access; this.commands=commands; this.validator=validator; this.photos=photos;
    }
    private record Context(WorkflowAccess.Scope scope,com.pis.accession.RequestContracts.Detail request,UUID caseId,String caseNumber) { }
    private Context context(UUID rid) {
        var request=requests.detail(rid); WorkflowAccess.Scope scope;
        try { scope=access.require(request.scopeId(),Permission.GROSS); } catch(org.springframework.security.access.AccessDeniedException e) { throw missing(); }
        if(!request.state().equals("RECEIVED")) throw conflict("GROSS_REQUIRES_RECEIVED");
        var cases=jdbc.query("SELECT c.id,c.case_number FROM specimen_reception s JOIN pathology_case c ON c.id=s.case_id WHERE s.request_id=?",(r,i)->new Context(scope,request,r.getObject(1,UUID.class),r.getString(2)),rid);
        if(cases.size()!=1) throw conflict("GROSS_REQUIRES_RECEIVED"); return cases.getFirst();
    }
    /** Public material-release projection for technical tasks; no GROSS editing grant is implied. */
    public record Released(UUID requestId,long requestVersion,UUID scopeId,UUID hospitalId,UUID caseId,String caseNumber,UUID recordId,UUID patientId,String patientLabel,String encounterNumber,List<ReleasedCassette> cassettes) { }
    public record ReleasedCassette(UUID id,String number,String site,List<UUID> containerIds) { }
    private record ReleasedIdentity(UUID recordId,UUID hospitalId,UUID caseId,String caseNumber) { }
    @Transactional(timeout=10)
    public Released released(UUID rid) {
        var q=requests.detail(rid);
        var rows=jdbc.query("SELECT g.id,g.hospital_id,g.case_id,c.case_number FROM gross_record g JOIN pathology_case c ON c.id=g.case_id WHERE g.request_id=? AND g.state='COMPLETED'",(r,i)->new ReleasedIdentity(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4)),rid);
        if(!q.state().equals("RECEIVED")||rows.size()!=1) throw conflict("TECH_SOURCE_NOT_READY");
        var r=rows.getFirst(); var sources=new HashMap<UUID,List<UUID>>();
        jdbc.query("SELECT cassette_id,container_id FROM gross_cassette_source WHERE record_id=? ORDER BY cassette_id,container_id LIMIT 1000",row->{ sources.computeIfAbsent(row.getObject(1,UUID.class),key->new ArrayList<>()).add(row.getObject(2,UUID.class)); },r.recordId());
        var boxes=jdbc.query("SELECT id,cassette_number,site FROM gross_cassette WHERE record_id=? AND state='PLANNED' ORDER BY cassette_number LIMIT 50",(row,i)->new ReleasedCassette(row.getObject(1,UUID.class),row.getString(2),row.getString(3),sources.getOrDefault(row.getObject(1,UUID.class),List.of())),r.recordId());
        return new Released(rid,q.version(),q.scopeId(),r.hospitalId(),r.caseId(),r.caseNumber(),r.recordId(),q.patientId(),q.patientLabel(),q.encounterNumber(),boxes);
    }
    private UUID requestId(UUID recordId) {
        access.actor(); var rows=jdbc.queryForList("SELECT request_id FROM gross_record WHERE id=?",UUID.class,recordId);
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private static Instant time(OffsetDateTime t) { return t==null?null:t.toInstant(); }
    @Transactional(timeout=10)
    public View view(UUID rid) {
        context(rid);
        // All grossing writes lock this parent first; keep the revision and version snapshot coherent.
        jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);
        var c=context(rid); var ids=jdbc.queryForList("SELECT id FROM gross_record WHERE case_id=?",UUID.class,c.caseId());
        if(ids.isEmpty()) return new View(c.request(),c.caseId(),c.caseNumber(),null);
        UUID id=ids.getFirst();
        var revisions=jdbc.query("SELECT * FROM gross_revision WHERE record_id=? ORDER BY record_version DESC LIMIT 50",(r,i)->new Revision(r.getLong("record_version"),r.getString("description"),r.getString("reason"),r.getObject("actor_id",UUID.class),time(r.getObject("created_at",OffsetDateTime.class))),id);
        var sourceMap=new HashMap<UUID,List<UUID>>();
        jdbc.query("SELECT cassette_id,container_id FROM gross_cassette_source WHERE record_id=? ORDER BY cassette_id,container_id",r->{ sourceMap.computeIfAbsent(r.getObject(1,UUID.class),key->new ArrayList<>()).add(r.getObject(2,UUID.class)); },id);
        var cassettes=jdbc.query("SELECT * FROM gross_cassette WHERE record_id=? ORDER BY cassette_number LIMIT 50",(r,i)->new Cassette(r.getObject("id",UUID.class),r.getString("cassette_number"),r.getString("site"),r.getInt("pieces"),r.getString("state"),r.getLong("version"),sourceMap.getOrDefault(r.getObject("id",UUID.class),List.of())),id);
        var photoList=jdbc.query("SELECT * FROM gross_photo WHERE record_id=? ORDER BY created_at,id LIMIT 5",(r,i)->new Photo(r.getObject("id",UUID.class),r.getObject("container_id",UUID.class),r.getString("caption"),r.getString("sha256"),r.getInt("byte_count"),r.getInt("width"),r.getInt("height"),time(r.getObject("withdrawn_at",OffsetDateTime.class))),id);
        var events=jdbc.query("SELECT * FROM gross_event WHERE record_id=? ORDER BY record_version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("record_version"),r.getString("action"),r.getObject("target_id",UUID.class),r.getString("reason"),r.getObject("actor_id",UUID.class),time(r.getObject("occurred_at",OffsetDateTime.class))),id);
        var record=jdbc.queryForObject("SELECT state,version FROM gross_record WHERE id=?",(r,i)->new GrossContracts.Record(id,r.getString(1),r.getLong(2),revisions.getFirst().description(),cassettes,photoList,revisions,events),id);
        return new View(c.request(),c.caseId(),c.caseNumber(),record);
    }
    @Transactional(timeout=10)
    public byte[] sample(UUID rid) { context(rid); var object=photos.sample(); return photos.read(object.key(),object.sha256()); }
    @Transactional(timeout=10)
    public byte[] photo(UUID photoId) {
        access.actor(); var ids=jdbc.queryForList("SELECT record_id FROM gross_photo WHERE id=?",UUID.class,photoId);
        if(ids.isEmpty()) throw missing(); var rid=requestId(ids.getFirst()); context(rid);
        jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid);
        context(rid);
        if(jdbc.queryForObject("SELECT state FROM gross_record WHERE id=?",String.class,ids.getFirst()).equals("CANCELLED")) throw conflict("GROSS_PHOTO_WITHDRAWN");
        return jdbc.queryForObject("SELECT object_key,sha256,withdrawn_at FROM gross_photo WHERE id=?",(r,i)->{
            if(r.getObject(3)!=null) throw conflict("GROSS_PHOTO_WITHDRAWN"); return photos.read(r.getString(1),r.getString(2));
        },photoId);
    }
    private void validate(Object input) { var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors); }
    public IdempotentCommands.Result create(UUID rid,Create input,String key) { return command(rid,null,null,input,key,"CREATE",input.requestVersion()); }
    public IdempotentCommands.Result describe(UUID id,Description input,String key,boolean correction) { return command(requestId(id),id,null,input,key,correction?"CORRECT":"SAVE",input.expectedVersion()); }
    public IdempotentCommands.Result addCassette(UUID id,AddCassette input,String key) { return command(requestId(id),id,null,input,key,"ADD_CASSETTE",input.expectedVersion()); }
    public IdempotentCommands.Result addPhoto(UUID id,AddPhoto input,String key) { return command(requestId(id),id,null,input,key,"ADD_PHOTO",input.expectedVersion()); }
    public IdempotentCommands.Result decide(UUID id,UUID target,Decision input,String key,String action) {
        if(!List.of("COMPLETE","CANCEL","CANCEL_CASSETTE","WITHDRAW_PHOTO").contains(action)) throw new IllegalArgumentException("Unknown grossing action");
        return command(requestId(id),id,target,input,key,action,input.expectedVersion());
    }
    private void sources(Context c,List<UUID> ids) {
        if(new HashSet<>(ids).size()!=ids.size()) throw conflict("GROSS_SOURCE_MISMATCH");
        var args=new ArrayList<Object>(); args.add(c.caseId()); args.addAll(ids);
        var found=jdbc.queryForList("SELECT id FROM specimen_container WHERE case_id=? AND received_at IS NOT NULL AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id FOR SHARE",UUID.class,args.toArray());
        if(found.size()!=ids.size()) throw conflict("GROSS_SOURCE_MISMATCH");
    }
    private IdempotentCommands.Result command(UUID rid,UUID id,UUID target,Object input,String key,String action,Long expected) {
        validate(input); var initial=context(rid);
        PhotoStore.ObjectInfo object=input instanceof AddPhoto p?photos.validateSynthetic(p.base64()):null;
        return commands.execute(initial.scope().hospitalId(),"GROSS_"+action+"_V1",key,Map.of("request",rid,"record",id==null?"":id.toString(),"target",target==null?"":target.toString(),"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { context(rid); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { context(requestId(receipt.resourceId())); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid);
                var c=context(rid); UUID result=id; long version; Long previous=null; UUID eventTarget=target;
                String reason=input instanceof Description d?d.reason():input instanceof Decision d?d.reason():"Synthetic "+action;
                if(action.equals("CREATE")) {
                    if(c.request().version()!=expected) throw conflict("VERSION_CONFLICT");
                    if(jdbc.queryForObject("SELECT count(*) FROM gross_record WHERE case_id=?",Long.class,c.caseId())>0) throw conflict("GROSS_ALREADY_EXISTS");
                    result=UUID.randomUUID(); version=0;
                    jdbc.update("INSERT INTO gross_record(id,hospital_id,request_id,case_id,state,created_by) VALUES(?,?,?,?,'DRAFT',?)",result,c.scope().hospitalId(),rid,c.caseId(),actor.id());
                    revision(result,0,((Create)input).description(),reason,actor.id());
                } else {
                    jdbc.queryForList("SELECT id FROM gross_record WHERE id=? FOR UPDATE",id);
                    long current=jdbc.queryForObject("SELECT version FROM gross_record WHERE id=?",Long.class,id);
                    if(current!=expected) throw conflict("VERSION_CONFLICT");
                    String state=jdbc.queryForObject("SELECT state FROM gross_record WHERE id=?",String.class,id);
                    if(!state.equals(action.equals("CORRECT")?"COMPLETED":"DRAFT")) throw conflict("GROSS_STATE_CONFLICT");
                    previous=current; version=current+1;
                    switch(action) {
                        case "SAVE","CORRECT" -> {
                            String text=((Description)input).description();
                            if(action.equals("CORRECT")&&text.isBlank()) throw conflict("GROSS_INCOMPLETE");
                            revision(id,version,text,reason,actor.id());
                        }
                        case "ADD_CASSETTE" -> {
                            var box=(AddCassette)input; sources(c,box.containerIds());
                            for(UUID source:box.containerIds())if(jdbc.queryForObject("SELECT count(*) FROM cytology_specimen WHERE container_id=?",Long.class,source)>0)throw conflict("CYTOLOGY_LEDGER_REQUIRED");
                            if(jdbc.queryForObject("SELECT count(*) FROM gross_cassette WHERE record_id=?",Long.class,id)>=50) throw conflict("GROSS_LIMIT_REACHED");
                            eventTarget=UUID.randomUUID();
                            jdbc.update("INSERT INTO gross_cassette(id,record_id,hospital_id,request_id,case_id,cassette_number,site,pieces,state) VALUES(?,?,?,?,?,?,?,?,'PLANNED')",eventTarget,id,c.scope().hospitalId(),rid,c.caseId(),"DEV-C-"+eventTarget,box.site(),box.pieces());
                            for(UUID source:box.containerIds()) jdbc.update("INSERT INTO gross_cassette_source(cassette_id,container_id,record_id,hospital_id,request_id,case_id) VALUES(?,?,?,?,?,?)",eventTarget,source,id,c.scope().hospitalId(),rid,c.caseId());
                        }
                        case "CANCEL_CASSETTE" -> { if(jdbc.update("UPDATE gross_cassette SET state='CANCELLED',version=version+1 WHERE id=? AND record_id=? AND state='PLANNED'",target,id)!=1) throw conflict("GROSS_TARGET_MISMATCH"); }
                        case "ADD_PHOTO" -> {
                            var photo=(AddPhoto)input; sources(c,List.of(photo.containerId()));
                            if(jdbc.queryForObject("SELECT count(*) FROM gross_photo WHERE record_id=?",Long.class,id)>=5) throw conflict("GROSS_LIMIT_REACHED");
                            eventTarget=UUID.randomUUID();
                            jdbc.update("INSERT INTO gross_photo(id,record_id,hospital_id,request_id,case_id,container_id,object_key,sha256,byte_count,width,height,caption,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",eventTarget,id,c.scope().hospitalId(),rid,c.caseId(),photo.containerId(),object.key(),object.sha256(),object.bytes(),object.width(),object.height(),photo.caption(),actor.id());
                        }
                        case "WITHDRAW_PHOTO" -> { if(jdbc.update("UPDATE gross_photo SET withdrawn_at=statement_timestamp() WHERE id=? AND record_id=? AND withdrawn_at IS NULL",target,id)!=1) throw conflict("GROSS_TARGET_MISMATCH"); }
                        case "COMPLETE" -> {
                            var text=jdbc.queryForObject("SELECT description FROM gross_revision WHERE record_id=? ORDER BY record_version DESC LIMIT 1",String.class,id);
                            if(text.isBlank()||jdbc.queryForObject("SELECT count(*) FROM gross_cassette WHERE record_id=? AND state='PLANNED'",Long.class,id)==0) throw conflict("GROSS_INCOMPLETE");
                            state="COMPLETED";
                        }
                        case "CANCEL" -> { state="CANCELLED"; jdbc.update("UPDATE gross_cassette SET state='CANCELLED',version=version+1 WHERE record_id=? AND state='PLANNED'",id); }
                        default -> throw new IllegalArgumentException("Unknown grossing action");
                    }
                    if(jdbc.update("UPDATE gross_record SET state=?,version=version+1 WHERE id=? AND version=?",state,id,current)!=1) throw conflict("VERSION_CONFLICT");
                }
                jdbc.update("INSERT INTO gross_event(record_id,record_version,action,target_id,reason,actor_id) VALUES(?,?,?,?,?,?)",result,version,action,eventTarget,reason,actor.id());
                return new IdempotentCommands.Mutation(new CommandReceipt(action.equals("CREATE")?201:200,"GROSS_RECORD",result,version),previous);
            }
        });
    }
    private void revision(UUID id,long version,String text,String reason,UUID actor) { jdbc.update("INSERT INTO gross_revision(record_id,record_version,description,reason,actor_id) VALUES(?,?,?,?,?)",id,version,text,reason,actor); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"GROSS_NOT_FOUND","Grossing resource is not available"); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Grossing operation requires refresh or review"); }
}
