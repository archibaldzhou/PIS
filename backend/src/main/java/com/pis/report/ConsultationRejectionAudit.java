package com.pis.report;
import java.util.UUID;
import com.pis.audit.CurrentActor;
import com.pis.api.TraceIdFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
@Component
public class ConsultationRejectionAudit {
 private final JdbcTemplate jdbc;private final CurrentActor actors;
 public ConsultationRejectionAudit(JdbcTemplate jdbc,CurrentActor actors){this.jdbc=jdbc;this.actors=actors;}
 @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
 public void record(UUID hospital,UUID request,String action,String code){var actor=actors.require();jdbc.update("INSERT INTO consultation_rejection(id,hospital_id,request_id,actor_id,action,code,trace_id) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),hospital,request,actor.id(),action,code,TraceIdFilter.currentTraceId());}
}
