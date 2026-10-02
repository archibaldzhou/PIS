package com.pis.accession;

import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public scope boundary shared by request and reception use cases; does not expand CASE permissions. */
@Component
public class WorkflowAccess {
    private final JdbcTemplate jdbc;
    private final CurrentActor actors;
    private final boolean enabled;
    public WorkflowAccess(JdbcTemplate jdbc, CurrentActor actors, Environment environment,
            @Value("${pis.workflow.development-enabled:false}") boolean enabled) {
        if (enabled && (!environment.acceptsProfiles(Profiles.of("dev", "test")) || environment.acceptsProfiles(Profiles.of("prod")))) {
            throw new IllegalStateException("Development workflow requires an isolated dev/test profile");
        }
        this.jdbc = jdbc; this.actors = actors; this.enabled = enabled;
    }
    public CurrentActor.Actor actor() {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_DISABLED", "Development workflow is disabled");
        var actor = actors.require();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT synthetic_only FROM app_user WHERE id = ?", Boolean.class, actor.id()))) {
            throw new AccessDeniedException("Synthetic workflow only");
        }
        return actor;
    }
    public Scope require(UUID scopeId, boolean write) {
        var actor = actor();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR SHARE", actor.id());
            jdbc.queryForList("SELECT id FROM workflow_scope WHERE id = ? FOR SHARE", scopeId);
            jdbc.queryForList("SELECT user_id FROM workflow_grant WHERE user_id = ? AND scope_id = ? FOR SHARE", actor.id(), scopeId);
            actor = actor(); // Fresh statement after every lock wait, including account revocation.
        }
        var rows = jdbc.query("""
            SELECT s.* FROM workflow_scope s JOIN workflow_grant g ON g.scope_id = s.id
            WHERE s.id = ? AND s.enabled AND g.user_id = ? AND g.can_read AND (NOT ? OR g.can_write)
              AND g.revoked_at IS NULL AND g.valid_from <= statement_timestamp()
              AND (g.valid_until IS NULL OR g.valid_until > statement_timestamp())
            """, (r, i) -> new Scope(r.getObject("id", UUID.class), r.getObject("hospital_id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getObject("department_id", UUID.class),
                r.getObject("source_system_id", UUID.class), r.getString("name")), scopeId, actor.id(), write);
        if (rows.isEmpty()) throw new AccessDeniedException("Workflow scope is not permitted");
        return rows.getFirst();
    }
    public record Scope(UUID id, UUID hospitalId, UUID campusId, UUID departmentId, UUID sourceId, String name) { }
}
