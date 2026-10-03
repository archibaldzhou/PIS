package com.pis.frozen;
import java.util.UUID;
import com.pis.audit.CurrentActor;
import com.pis.api.TraceIdFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
/** Authorized-case business refusals survive rollback; never stores supplied payload or foreign IDs. */
@Component
public class FrozenRejectionAudit {
 private final JdbcTemplate jdbc;private final CurrentActor actors;
 public FrozenRejectionAudit(JdbcTemplate jdbc,CurrentActor actors){this.jdbc=jdbc;this.actors=actors;}
 @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
 public void record(UUID hospital,UUID caseId,String action,String code){var actor=actors.require();jdbc.update("INSERT INTO frozen_rejection(id,hospital_id,case_id,actor_id,action,code,trace_id) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),hospital,caseId,actor.id(),action,code,TraceIdFilter.currentTraceId());}
}
