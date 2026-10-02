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
    private final JdbcTemplate jdbc; private final RequestService requests; private final WorkflowAccess access;
    public MaterialQueries(JdbcTemplate jdbc,RequestService requests,WorkflowAccess access) { this.jdbc=jdbc; this.requests=requests; this.access=access; }
    static final RowMapper<Entity> MAPPER=(r,i)->new Entity(r.getObject("id",UUID.class),r.getObject("hospital_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("case_id",UUID.class),r.getObject("patient_id",UUID.class),r.getString("kind"),r.getString("route"),r.getString("operation"),r.getString("display_number"),r.getString("barcode"),r.getObject("record_id",UUID.class),r.getObject("cassette_id",UUID.class),r.getObject("container_id",UUID.class),r.getObject("block_id",UUID.class),r.getObject("source_slide_id",UUID.class),r.getObject("technical_task_id",UUID.class),r.getString("state"),r.getLong("version"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class).toInstant());
    Entity row(UUID id) { access.actor(); var rows=jdbc.query("SELECT * FROM material_entity WHERE id=?",MAPPER,id); if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"MATERIAL_NOT_FOUND","Material is not available"); return rows.getFirst(); }
    public Optional<Subject> find(UUID id) {
        access.actor(); var rows=jdbc.query("SELECT * FROM material_entity WHERE id=?",MAPPER,id); if(rows.isEmpty()) return Optional.empty(); var m=rows.getFirst(); var q=requests.detail(m.requestId());
        String number=jdbc.queryForObject("SELECT case_number FROM pathology_case WHERE id=?",String.class,m.caseId());
        boolean active=m.state().equals("ACTIVE") && (m.blockId()==null||row(m.blockId()).state().equals("ACTIVE"));
        return Optional.of(new Subject(id,null,id,m.hospitalId(),q.id(),q.version(),m.version(),q.scopeId(),q.patientId(),q.patientLabel()==null?"Unknown synthetic patient":q.patientLabel(),q.encounterNumber(),q.requestNumber(),number,m.number()+" / "+m.kind()+" / "+m.route(),"UNKNOWN",q.state(),active));
    }
}
