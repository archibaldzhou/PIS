package com.pis.idempotency;

import com.pis.api.ApiException;
import com.pis.audit.AuditRecorder;
import com.pis.audit.CurrentActor;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns one short, local JDBC transaction. This is not an HTTP filter or an external exactly-once engine.
 * Callers must validate typed input and resolve/authorize the hospital from authoritative resources.
 * Mutation SQL must enforce current permission/ownership/version atomically; an earlier authorize()
 * call alone cannot prevent authorization races. Never perform network calls or AI work inside work.
 */
@Component
public final class IdempotentCommands {
    private final JdbcTemplate jdbc;
    private final CurrentActor actors;
    private final AuditRecorder audit;
    private final CanonicalRequestDigest digests;
    private final TransactionTemplate transaction;

    public IdempotentCommands(JdbcTemplate jdbc, CurrentActor actors, AuditRecorder audit,
                              CanonicalRequestDigest digests, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.actors = actors; this.audit = audit; this.digests = digests;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public Result execute(UUID hospitalId, String operation, String key, Object validatedCommand, Work work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IdempotentCommands must own its transaction; do not nest it in another transaction");
        }
        Objects.requireNonNull(hospitalId, "Server-resolved hospital is required");
        Objects.requireNonNull(work, "Command work is required");
        AuditRecorder.requireToken(operation, 128);
        var actor = actors.require();
        work.authorize(actor);
        var keyHash = digests.key(key);
        var requestDigest = digests.request(validatedCommand);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                // A blocked duplicate must not hold an HTTP request or pool connection indefinitely.
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                jdbc.execute("SET LOCAL statement_timeout = '5s'");
                var id = UUID.randomUUID();
                int inserted = jdbc.update("""
                    INSERT INTO idempotency_command (id, actor_user_id, hospital_id, operation_code,
                        key_hash, request_digest, digest_version, state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS')
                    ON CONFLICT (actor_user_id, hospital_id, operation_code, key_hash) DO NOTHING
                    """, id, actor.id(), hospitalId, operation, keyHash, requestDigest, CanonicalRequestDigest.VERSION);
                // The unique-key wait may outlive a permission change. Never reuse pre-wait authorization.
                var currentActor = actors.require();
                if (!actor.equals(currentActor)) throw new IllegalStateException("Authenticated command identity changed");
                work.authorize(currentActor);
                if (inserted == 0) {
                    // A separate READ COMMITTED statement is essential: the INSERT's snapshot can miss its winner.
                    var saved = jdbc.queryForObject("""
                        SELECT request_digest, digest_version, state, response_status, resource_type, resource_id, result_version
                        FROM idempotency_command WHERE actor_user_id = ? AND hospital_id = ?
                            AND operation_code = ? AND key_hash = ?
                        """, (row, index) -> new Saved(row.getBytes("request_digest"), row.getInt("digest_version"),
                            row.getString("state"), row.getInt("response_status"), row.getString("resource_type"),
                            row.getObject("resource_id", UUID.class), row.getLong("result_version")),
                        actor.id(), hospitalId, operation, keyHash);
                    if (saved == null || !"SUCCEEDED".equals(saved.state())) {
                        // No ordinary path commits IN_PROGRESS. A manual/corrupt row is never interpreted as success.
                        throw new ApiException(HttpStatus.CONFLICT, "COMMAND_BUSY", "The command is not ready; retry with the same key");
                    }
                    var receipt = new CommandReceipt(saved.status(), saved.resourceType(), saved.resourceId(), saved.version());
                    work.authorizeReplay(currentActor, receipt);
                    if (saved.digestVersion() != CanonicalRequestDigest.VERSION
                            || !MessageDigest.isEqual(saved.digest(), requestDigest)) {
                        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "The key was already used for a different command");
                    }
                    return new Result(receipt, true);
                }
                var mutation = Objects.requireNonNull(work.mutate(currentActor), "A mutation receipt is required");
                var receipt = mutation.receipt();
                audit.append(hospitalId, operation, receipt.resourceType(), receipt.resourceId(),
                    mutation.previousVersion(), receipt.version());
                int updated = jdbc.update("""
                    UPDATE idempotency_command SET state = 'SUCCEEDED', response_status = ?, resource_type = ?,
                        resource_id = ?, result_version = ?, completed_at = statement_timestamp()
                    WHERE id = ? AND state = 'IN_PROGRESS'
                    """, receipt.status(), receipt.resourceType(), receipt.resourceId(), receipt.version(), id);
                if (updated != 1) throw new IllegalStateException("Command reservation was not finalized");
                return new Result(receipt, false);
            }));
        } catch (TransactionTimedOutException failure) {
            // Spring may enforce the transaction deadline before issuing the next JDBC statement.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COMMAND_TIMEOUT", "The command timed out; retry with the same key");
        } catch (DataAccessException failure) {
            // Translation happens only after TransactionTemplate has rolled back the aborted transaction.
            if (hasSqlState(failure, "55P03")) {
                throw new ApiException(HttpStatus.CONFLICT, "COMMAND_BUSY", "The command is busy; retry with the same key");
            }
            if (hasSqlState(failure, "57014")) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COMMAND_TIMEOUT", "The command timed out; retry with the same key");
            }
            throw failure;
        }
    }

    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    public interface Work {
        /** Verify current permission for the command and authoritative hospital/resource scope. */
        void authorize(CurrentActor.Actor actor);
        /** Enforce current authorization, ownership and optimistic version in the actual atomic write. */
        Mutation mutate(CurrentActor.Actor actor);
        /** Recheck the current permission for the saved resource, even if request preauthorization passed. */
        void authorizeReplay(CurrentActor.Actor actor, CommandReceipt receipt);
    }

    public record Mutation(CommandReceipt receipt, Long previousVersion) {
        public Mutation {
            Objects.requireNonNull(receipt, "A mutation receipt is required");
            if (previousVersion != null && (previousVersion < 0 || receipt.version() <= previousVersion)) {
                throw new IllegalArgumentException("Invalid mutation version progression");
            }
        }
    }
    public record Result(CommandReceipt receipt, boolean replayed) { }
    private record Saved(byte[] digest, int digestVersion, String state, int status, String resourceType,
                         UUID resourceId, long version) { }
}
