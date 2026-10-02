package com.pis.idempotency;

import com.pis.api.ApiException;
import com.pis.api.TraceIdFilter;
import com.pis.audit.AuditRecorder;
import com.pis.audit.CurrentActor;
import com.pis.database.PostgresTestDatabase;
import com.pis.security.AccountRepository;
import com.pis.security.PisPrincipal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Non-clinical command targets and permissions exist only inside this disposable test schema. */
final class CommandTestFixture implements AutoCloseable {
    final PostgresTestDatabase database = new PostgresTestDatabase();
    final AbstractDataSource source = new AbstractDataSource() {
        @Override public Connection getConnection() throws SQLException {
            var connection = database.connection();
            connection.setClientInfo("ApplicationName", database.schema());
            return connection;
        }
        @Override public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }
    };
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final CurrentActor actors = new CurrentActor(jdbc);
    final AuditRecorder audit = new AuditRecorder(jdbc, actors);
    final IdempotentCommands commands = new IdempotentCommands(jdbc, actors, audit,
        new CanonicalRequestDigest(JsonMapper.builder().build()), new JdbcTransactionManager(source));
    final UUID hospital = UUID.randomUUID();
    final UUID resource = UUID.randomUUID();
    final UUID user = UUID.randomUUID();
    final PisPrincipal principal;

    CommandTestFixture() {
        database.configuration("classpath:db/migration").load().migrate();
        jdbc.execute("CREATE TABLE command_test_resource (id uuid PRIMARY KEY, hospital_id uuid NOT NULL REFERENCES hospital(id), version bigint NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE command_test_permission (actor_id uuid NOT NULL REFERENCES app_user(id), hospital_id uuid NOT NULL, resource_id uuid NOT NULL, enabled boolean NOT NULL DEFAULT true, PRIMARY KEY(actor_id, hospital_id, resource_id))");
        jdbc.update("INSERT INTO hospital(id, code, name) VALUES (?, 'command-test', 'Synthetic command hospital')", hospital);
        jdbc.update("INSERT INTO command_test_resource(id, hospital_id) VALUES (?, ?)", resource, hospital);
        principal = addActor(user, "synthetic-command-user", hospital, resource);
    }

    PisPrincipal addActor(UUID id, String username, UUID hospitalId, UUID resourceId) {
        jdbc.update("INSERT INTO app_user(id, username, display_name, password_hash, enabled) VALUES (?, ?, 'Synthetic test user', 'unused-synthetic-test-hash', true)", id, username);
        jdbc.update("INSERT INTO command_test_permission(actor_id, hospital_id, resource_id) VALUES (?, ?, ?)", id, hospitalId, resourceId);
        return new PisPrincipal(new AccountRepository(jdbc).findByUsername(username).orElseThrow());
    }

    IdempotentCommands.Result execute(String key, Object body, long expectedVersion) {
        return request(principal, () -> commands.execute(hospital, "TEST_CHANGE_V1", key, body, work(hospital, resource, expectedVersion, () -> { }, () -> { })));
    }

    IdempotentCommands.Work work(UUID hospitalId, UUID resourceId, long expectedVersion, Runnable before, Runnable after) {
        return new IdempotentCommands.Work() {
            @Override public void authorize(CurrentActor.Actor actor) { requirePermission(actor, hospitalId, resourceId); }
            @Override public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                before.run();
                // This test write includes current permission, identity state, ownership and version in ONE statement.
                var versions = jdbc.query("""
                    UPDATE command_test_resource target SET version = target.version + 1
                    WHERE target.id = ? AND target.hospital_id = ? AND target.version = ? AND EXISTS (
                        SELECT 1 FROM command_test_permission p JOIN app_user u ON u.id = p.actor_id
                        WHERE p.actor_id = ? AND p.hospital_id = target.hospital_id AND p.resource_id = target.id
                            AND p.enabled AND u.enabled AND u.auth_version = ?)
                    RETURNING version
                    """, (row, index) -> row.getLong(1), resourceId, hospitalId, expectedVersion, actor.id(), actor.authVersion());
                if (versions.isEmpty()) {
                    authorize(actor);
                    throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The resource version has changed");
                }
                after.run();
                return new IdempotentCommands.Mutation(new CommandReceipt(200, "TEST_RESOURCE", resourceId, versions.getFirst()), expectedVersion);
            }
            @Override public void authorizeReplay(CurrentActor.Actor actor, CommandReceipt receipt) {
                requirePermission(actor, hospitalId, receipt.resourceId());
            }
        };
    }

    void requirePermission(CurrentActor.Actor actor, UUID hospitalId, UUID resourceId) {
        boolean permitted = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM command_test_permission p JOIN command_test_resource r
                ON r.id = p.resource_id AND r.hospital_id = p.hospital_id
                WHERE p.actor_id = ? AND p.hospital_id = ? AND p.resource_id = ? AND p.enabled)
            """, Boolean.class, actor.id(), hospitalId, resourceId));
        if (!permitted) throw new AccessDeniedException("Synthetic command permission denied");
    }

    void awaitBlockedReservation() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        do {
            int waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE '%INSERT INTO idempotency_command%'
                """, Integer.class, database.schema());
            if (waiting > 0) return;
            try { Thread.sleep(10); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("The competing command did not reach the PostgreSQL unique-key wait");
    }

    int count(String table) {
        if (!List.of("audit_event", "idempotency_command").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
    long version() { return jdbc.queryForObject("SELECT version FROM command_test_resource WHERE id = ?", Long.class, resource); }

    static <T> T request(PisPrincipal principal, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        var result = new AtomicReference<T>();
        try {
            new TraceIdFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> result.set(action.get()));
            return result.get();
        } catch (java.io.IOException | jakarta.servlet.ServletException failure) {
            throw new AssertionError(failure);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Override public void close() throws Exception { database.close(); }
}
