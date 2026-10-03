package com.pis.material;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.accession.WorkflowAccess.Permission;
import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import com.pis.label.LabelBarcode;
import com.pis.label.LabelService;
import com.pis.processing.TechnicalService;
import com.pis.specimen.ReceptionService;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pis.material.MaterialContracts.*;
@Service
public class MaterialService {
    private final com.pis.quality.QualityGate quality;
    private final JdbcTemplate jdbc; private final RequestService requests; private final WorkflowAccess access; private final ReceptionService reception;
    private final TechnicalService technical; private final LabelService labels; private final MaterialQueries queries; private final IdempotentCommands commands; private final Validator validator;
    public MaterialService(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access,ReceptionService reception,TechnicalService technical,LabelService labels,MaterialQueries queries,IdempotentCommands commands,Validator validator,com.pis.quality.QualityGate quality) {
        this.quality=quality; this.jdbc=jdbc; this.requests=requests; this.access=access; this.reception=reception; this.technical=technical; this.labels=labels; this.queries=queries; this.commands=commands; this.validator=validator;
    }
    /** Authorized nonclinical custody projection; includes quarantined/void items without making them usable. */
    public record ArchiveSource(UUID id,String kind,String barcode,long version,String status) { }
    @Transactional(timeout=10)
    public List<ArchiveSource> archiveSources(UUID rid) {
        var q=requests.detail(rid); access.require(q.scopeId(),Permission.READ); reception.receivedSource(rid);
        return jdbc.query("SELECT m.id,m.kind,m.barcode,m.version,CASE WHEN EXISTS(SELECT 1 FROM quality_head h WHERE h.request_id=m.request_id AND h.state='IDENTITY_MISMATCH') OR EXISTS(SELECT 1 FROM cytology_specimen s WHERE s.request_id=m.request_id AND s.qc_state='IDENTITY_MISMATCH') THEN 'IDENTITY_MISMATCH' WHEN m.state<>'ACTIVE' THEN m.state ELSE q.state END AS status FROM material_entity m JOIN workflow_quality_projection q ON q.id=m.id WHERE m.request_id=? ORDER BY m.id LIMIT 100",(r,i)->new ArchiveSource(r.getObject("id",UUID.class),r.getString("kind"),r.getString("barcode"),r.getLong("version"),r.getString("status")),rid);
    }
    private record Context(com.pis.accession.RequestContracts.Detail request,ReceptionService.ReceivedSource received) { }
    private Context context(UUID rid) {
        var q=requests.detail(rid);
        try { access.require(q.scopeId(),Permission.MATERIAL); } catch(org.springframework.security.access.AccessDeniedException error) { throw missing(); }
        return new Context(q,reception.receivedSource(rid));
    }
    @Transactional(timeout=10)
    public View view(UUID rid) {
        context(rid); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",rid); var c=context(rid);
        return new View(c.request(),c.received().caseNumber(),jdbc.query("SELECT * FROM material_entity WHERE request_id=? ORDER BY created_at,id LIMIT 100",MaterialQueries.MAPPER,rid),technical.materialTasks(rid));
    }
    @Transactional(timeout=10)
    public Detail detail(UUID id) {
        var row=queries.row(id); context(row.requestId()); jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR SHARE",row.requestId()); context(row.requestId());
        return new Detail(queries.row(id),jdbc.query("SELECT * FROM material_event WHERE material_id=? ORDER BY material_version DESC LIMIT 100",(r,i)->new Event(r.getObject("id",UUID.class),r.getLong("material_version"),r.getString("action"),r.getObject("related_id",UUID.class),r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class).toInstant()),id));
    }
    @Transactional(timeout=10)
    public Detail barcode(String barcode) {
        access.actor(); if(!LabelBarcode.valid(barcode)) throw missing(); var ids=jdbc.queryForList("SELECT id FROM material_entity WHERE barcode=?",UUID.class,barcode);
        if(ids.size()!=1) throw missing(); return detail(ids.getFirst());
    }
    public IdempotentCommands.Result block(UUID rid,BlockCreate input,String key) { return execute(rid,null,input,key,"BLOCK"); }
    public IdempotentCommands.Result direct(UUID rid,DirectCreate input,String key) { return execute(rid,null,input,key,"DIRECT"); }
    public IdempotentCommands.Result slide(UUID block,SlideCreate input,String key) { return execute(queries.row(block).requestId(),block,input,key,"SLIDE"); }
    public IdempotentCommands.Result repeat(UUID slide,Repeat input,String key,String action) {
        if(!List.of("RECUT","DEEPER").contains(action)) throw new IllegalArgumentException("Unknown derivation"); return execute(queries.row(slide).requestId(),slide,input,key,action);
    }
    public IdempotentCommands.Result voidMaterial(UUID id,VoidMaterial input,String key) { return execute(queries.row(id).requestId(),id,input,key,"VOID"); }
    private TechnicalService.MaterialTask task(Context c,UUID id,long version,String kind,UUID cassette) {
        var t=technical.materialTasks(c.request().id()).stream().filter(v->v.id().equals(id)).findFirst().orElseThrow(()->conflict("MATERIAL_TASK_NOT_READY"));
        quality.task(t.id(),t.version()); quality.cassette(c.request().id(),t.cassetteId(),t.id());
        if(t.version()!=version) throw conflict("VERSION_CONFLICT");
        if(!t.kind().equals(kind)||!t.caseId().equals(c.received().caseId())||(cassette!=null&&!cassette.equals(t.cassetteId()))) throw conflict("MATERIAL_SOURCE_MISMATCH"); return t;
    }
    private void active(Entity e) { if(!e.state().equals("ACTIVE")) throw conflict("MATERIAL_INACTIVE"); }
    private void identity(UUID actual,UUID confirmed) { if(!actual.equals(confirmed)) throw conflict("MATERIAL_SOURCE_MISMATCH"); }
    private void version(long actual,long expected) { if(actual!=expected) throw conflict("VERSION_CONFLICT"); }
    private void event(UUID id,long version,String action,UUID related,String reason,UUID actor) {
        jdbc.update("INSERT INTO material_event(material_id,material_version,action,related_id,reason,actor_id) VALUES(?,?,?,?,?,?)",id,version,action,related,reason,actor);
    }
    private void bump(Entity source,String action,UUID related,String reason,UUID actor) {
        if(jdbc.update("UPDATE material_entity SET version=version+1 WHERE id=? AND version=?",source.id(),source.version())!=1) throw conflict("VERSION_CONFLICT");
        quality.invalidateMaterial(source.id(),actor,reason);
        event(source.id(),source.version()+1,action,related,reason,actor);
    }
    private IdempotentCommands.Result execute(UUID rid,UUID parent,Object input,String key,String action) {
        var errors=validator.validate(input); if(!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors);
        var initial=context(rid);
        return commands.execute(initial.received().hospitalId(),"MATERIAL_"+action+"_V1",key,Map.of("request",rid,"parent",parent==null?"":parent.toString(),"command",input),new IdempotentCommands.Work() {
            public void authorize(CurrentActor.Actor actor) { context(rid); }
            public void authorizeReplay(CurrentActor.Actor actor,CommandReceipt receipt) { context(queries.row(receipt.resourceId()).requestId()); }
            public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                jdbc.queryForList("SELECT id FROM pathology_request WHERE id=? FOR UPDATE",rid); var c=context(rid);
                // Parent request serializes all material and label mutations for this case.
                jdbc.queryForList("SELECT id FROM material_entity WHERE request_id=? ORDER BY id FOR UPDATE",rid);
                if(action.equals("VOID")) {
                    var v=(VoidMaterial)input; var root=queries.row(parent); identity(parent,v.confirmedMaterialId()); version(root.version(),v.expectedVersion()); active(root);
                    var affected=jdbc.query("SELECT * FROM material_entity WHERE state='ACTIVE' AND (id=? OR (? AND block_id=?)) ORDER BY id",MaterialQueries.MAPPER,parent,root.kind().equals("BLOCK"),parent);
                    for(var item:affected) {
                        if(jdbc.update("UPDATE material_entity SET state='VOID',version=version+1 WHERE id=? AND version=? AND state='ACTIVE'",item.id(),item.version())!=1) throw conflict("VERSION_CONFLICT");
                        quality.invalidateMaterial(item.id(),actor.id(),v.reason());
                        event(item.id(),item.version()+1,item.id().equals(parent)?"VOID":"SOURCE_VOIDED",item.id().equals(parent)?null:parent,v.reason(),actor.id());
                    }
                    return new IdempotentCommands.Mutation(new CommandReceipt(200,"MATERIAL",parent,root.version()+1),root.version());
                }
                if(jdbc.queryForObject("SELECT count(*) FROM material_entity WHERE request_id=?",Long.class,rid)>=100) throw conflict("MATERIAL_LIMIT_REACHED");
                UUID id=UUID.randomUUID(), record=null,cassette=null,container=null,block=null,sourceSlide=null,taskId=null;
                String kind=action.equals("BLOCK")?"BLOCK":"SLIDE", route,operation="ORIGINAL",reason; Entity source=null;
                if(input instanceof BlockCreate v) {
                    version(c.request().version(),v.requestVersion()); var t=task(c,v.taskId(),v.taskVersion(),"EMBEDDING",v.confirmedCassetteId());
                    if(jdbc.queryForObject("SELECT count(*) FROM material_entity WHERE technical_task_id=? AND kind='BLOCK'",Long.class,t.id())>0) throw conflict("MATERIAL_BLOCK_EXISTS");
                    record=t.recordId(); cassette=t.cassetteId(); taskId=t.id(); route="CASSETTE"; reason=v.reason();
                } else if(input instanceof DirectCreate v) {
                    version(c.request().version(),v.requestVersion()); container=v.confirmedContainerId();
                    UUID checkedContainer=container;
                    if(c.request().containers().stream().noneMatch(vv->vv.id().equals(checkedContainer))) throw conflict("MATERIAL_SOURCE_MISMATCH");
                    if(jdbc.queryForObject("SELECT count(*) FROM cytology_specimen WHERE container_id=?",Long.class,container)>0)throw conflict("CYTOLOGY_LEDGER_REQUIRED");
                    route="DIRECT_CYTOLOGY"; reason=v.reason();
                } else {
                    source=queries.row(parent); Entity blockSource;
                    if(input instanceof SlideCreate v) {
                        identity(parent,v.confirmedBlockId()); version(source.version(),v.blockVersion());
                        if(!source.kind().equals("BLOCK")||!source.route().equals("CASSETTE")) throw conflict("MATERIAL_SOURCE_MISMATCH"); blockSource=source; reason=v.reason();
                        var t=task(c,v.taskId(),v.taskVersion(),"SECTIONING",source.cassetteId()); taskId=t.id();
                    } else {
                        var v=(Repeat)input; identity(parent,v.confirmedSourceSlideId()); version(source.version(),v.sourceSlideVersion());
                        if(!source.kind().equals("SLIDE")||!source.route().equals("BLOCK_BASED")) throw conflict("MATERIAL_ROUTE_UNSUPPORTED");
                        quality.repair(source.id(),v.taskId()); blockSource=queries.row(source.blockId()); sourceSlide=parent; operation=action; reason=v.reason();
                        var t=task(c,v.taskId(),v.taskVersion(),"SECTIONING",blockSource.cassetteId()); taskId=t.id();
                    }
                    active(blockSource); queries.requireQuality(blockSource); block=blockSource.id(); record=blockSource.recordId(); cassette=blockSource.cassetteId(); route="BLOCK_BASED";
                }
                String number=(kind.equals("BLOCK")?"DEV-B-":"DEV-S-")+id,barcode=LabelBarcode.create(id);
                jdbc.update("""
                    INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,record_id,cassette_id,container_id,block_id,source_slide_id,technical_task_id,created_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,c.received().hospitalId(),c.request().patientId(),rid,c.received().caseId(),kind,route,operation,number,barcode,record,cassette,container,block,sourceSlide,taskId,actor.id());
                labels.registerMaterialIdentity(id,barcode);
                event(id,0,operation.equals("ORIGINAL")?"CREATE":operation,sourceSlide==null?block:sourceSlide,reason,actor.id());
                if(source!=null) bump(source,action.equals("SLIDE")?"DERIVE":action,id,reason,actor.id());
                return new IdempotentCommands.Mutation(new CommandReceipt(201,"MATERIAL",id,0),null);
            }
        });
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"MATERIAL_NOT_FOUND","Material is not available"); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Material identity requires refresh or review"); }
}
