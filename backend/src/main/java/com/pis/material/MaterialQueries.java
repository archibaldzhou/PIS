package com.pis.material;
import com.pis.accession.RequestService;
import com.pis.accession.WorkflowAccess;
import com.pis.api.ApiException;
import com.pis.label.LabelSubjectProvider;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import static com.pis.material.MaterialContracts.*;
@Component
public class MaterialQueries implements LabelSubjectProvider {
    private final com.pis.quality.QualityGate quality;
    private final com.pis.processing.TechnicalService technical;
    private final JdbcTemplate jdbc; private final RequestService requests; private final WorkflowAccess access;
    public MaterialQueries(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access,com.pis.quality.QualityGate quality,com.pis.processing.TechnicalService technical) { this.technical=technical; this.quality=quality; this.jdbc=jdbc; this.requests=requests; this.access=access; }
    static final RowMapper<Entity> MAPPER=(r,i)->new Entity(r.getObject("id",UUID.class),r.getObject("hospital_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("case_id",UUID.class),r.getObject("patient_id",UUID.class),r.getString("kind"),r.getString("route"),r.getString("operation"),r.getString("display_number"),r.getString("barcode"),r.getObject("record_id",UUID.class),r.getObject("cassette_id",UUID.class),r.getObject("container_id",UUID.class),r.getObject("block_id",UUID.class),r.getObject("source_slide_id",UUID.class),r.getObject("technical_task_id",UUID.class),r.getString("state"),r.getLong("version"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("cytology_preparation_id",UUID.class),r.getObject("stain_order_id",UUID.class));
    Entity row(UUID id) { access.actor(); var rows=jdbc.query("SELECT * FROM material_entity WHERE id=?",MAPPER,id); if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"MATERIAL_NOT_FOUND","Material is not available"); return rows.getFirst(); }
    void requireQuality(Entity m) {
        var task=m.technicalTaskId()==null?null:technical.qualityTasks(m.requestId()).stream().filter(t->t.id().equals(m.technicalTaskId())).findFirst().orElseThrow();
        if(task!=null&&!task.state().equals("SIMULATED_DONE")) throw com.pis.quality.QualityGate.blocked();
        Long taskVersion=task==null?null:task.version();
        quality.material(m.id(),m.version(),taskVersion);
        if(m.blockId()!=null) requireQuality(row(m.blockId()));
    }
    public Optional<Subject> find(UUID id) { return find(id,false); }
    public Optional<Subject> findForRegistration(UUID id) { return find(id,true); }
    private Optional<Subject> find(UUID id,boolean registration) {
        access.actor(); var rows=jdbc.query("SELECT * FROM material_entity WHERE id=?",MAPPER,id); if(rows.isEmpty()) return Optional.empty(); var m=rows.getFirst(); var q=requests.detail(m.requestId());
        boolean pendingStain=registration&&m.route().equals("STAINED_SLIDE")&&m.version()==0&&jdbc.queryForObject("SELECT count(*) FROM stain_member WHERE output_id=? AND order_id=?",Long.class,id,m.stainOrderId())==1;
        if(!pendingStain)requireQuality(m);
        String number=jdbc.queryForObject("SELECT case_number FROM pathology_case WHERE id=?",String.class,m.caseId());
        boolean active=m.state().equals("ACTIVE") && (m.blockId()==null||row(m.blockId()).state().equals("ACTIVE"));
        return Optional.of(new Subject(id,null,id,m.hospitalId(),q.id(),q.version(),m.version(),q.scopeId(),q.patientId(),q.patientLabel()==null?"Unknown synthetic patient":q.patientLabel(),q.encounterNumber(),q.requestNumber(),number,m.number()+" / "+m.kind()+" / "+m.route(),"UNKNOWN",q.state(),active));
    }
}
