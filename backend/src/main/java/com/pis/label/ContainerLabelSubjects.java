package com.pis.label;
import com.pis.accession.RequestService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class ContainerLabelSubjects implements LabelSubjectProvider {
    private final JdbcTemplate jdbc; private final RequestService requests;
    public ContainerLabelSubjects(JdbcTemplate jdbc,RequestService requests) { this.jdbc=jdbc; this.requests=requests; }
    public Optional<Subject> find(UUID id) {
        var rows=jdbc.queryForList("SELECT request_id FROM specimen_container WHERE id=?",UUID.class,id); if(rows.isEmpty()) return Optional.empty();
        var q=requests.detail(rows.getFirst()); var c=q.containers().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();
        return Optional.of(jdbc.queryForObject("SELECT s.hospital_id,s.version,s.received_at,p.case_number FROM specimen_container s LEFT JOIN pathology_case p ON p.id=s.case_id WHERE s.id=?",(r,i)->new Subject(id,id,null,r.getObject(1,UUID.class),q.id(),q.version(),r.getLong(2),q.scopeId(),q.patientId(),q.patientLabel()==null?"Unknown synthetic patient":q.patientLabel(),q.encounterNumber(),q.requestNumber(),r.getString(4),c.site(),c.laterality(),q.state(),q.state().equals("RECEIVED")&&r.getObject(3)!=null),id));
    }
}
