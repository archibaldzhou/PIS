package com.pis.audit;

import com.pis.api.TraceIdFilter;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Successful mutation metadata only. It deliberately has no arbitrary payload or actor argument. */
@Component
public final class AuditRecorder {
    private final JdbcTemplate jdbc;
    private final CurrentActor actors;

    public AuditRecorder(JdbcTemplate jdbc, CurrentActor actors) { this.jdbc = jdbc; this.actors = actors; }

    public UUID append(UUID hospitalId, String operation, String resourceType, UUID resourceId,
                       Long previousVersion, long resultVersion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Mutation audit requires an active writable business transaction");
        }
        requireToken(operation, 128);
        requireToken(resourceType, 64);
        if (hospitalId == null || resourceId == null || resultVersion < 0
                || (previousVersion != null && (previousVersion < 0 || resultVersion <= previousVersion))) {
            throw new IllegalArgumentException("Invalid mutation audit metadata");
        }
        var actor = actors.require();
        var id = UUID.randomUUID();
        // No RETURNING: the deployment's audit writer needs INSERT, not SELECT privileges.
        jdbc.update("""
            INSERT INTO audit_event (id, actor_user_id, actor_auth_version, hospital_id, operation_code,
                resource_type, resource_id, previous_version, result_version, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, actor.id(), actor.authVersion(), hospitalId, operation, resourceType, resourceId,
            previousVersion, resultVersion, TraceIdFilter.currentTraceId());
        return id;
    }

    public static void requireToken(String value, int maximum) {
        if (value == null || value.length() > maximum || !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("Invalid server-defined operation or resource type");
        }
    }
}
