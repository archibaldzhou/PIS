package com.pis.idempotency;

import com.pis.api.ApiException;
import com.pis.audit.CurrentActor;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import static com.pis.idempotency.CommandTestFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class IdempotentCommandsTest {
    @Test void commitsOnceAndReplaysTheSameReceiptWithoutRepeatingMutationOrAudit() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            var body = Map.of("target", fixture.resource, "version", 0, "value", "synthetic");
            var first = fixture.execute("same-intent", body, 0);
            var replay = fixture.execute("same-intent", body, 0); // Stale expectedVersion is not reapplied on replay.
            assertThat(first.replayed()).isFalse();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.receipt()).isEqualTo(first.receipt());
            assertThat(fixture.version()).isEqualTo(1);
            assertThat(fixture.count("audit_event")).isEqualTo(1);
            assertThat(fixture.count("idempotency_command")).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT actor_user_id FROM audit_event", UUID.class)).isEqualTo(fixture.user);
            assertThatThrownBy(() -> fixture.execute("same-intent", Map.of("target", fixture.resource, "version", 0, "value", "different"), 0))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        }
    }

    @Test void rollsBackBusinessAuditAndReservationWhenBusinessOrAuditOrFinalizationFails() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            var body = Map.of("version", 0);
            assertThatThrownBy(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "failure", body,
                fixture.work(fixture.hospital, fixture.resource, 0, () -> { }, () -> { throw new IllegalStateException("synthetic failure"); }))))
                .isInstanceOf(IllegalStateException.class);
            assertEmpty(fixture);
            fixture.jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT synthetic_audit_failure CHECK (operation_code <> 'TEST_CHANGE_V1')");
            assertThatThrownBy(() -> fixture.execute("failure", body, 0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertEmpty(fixture);
            fixture.jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT synthetic_audit_failure");
            fixture.jdbc.execute("ALTER TABLE idempotency_command ADD CONSTRAINT synthetic_finalize_failure CHECK (state <> 'SUCCEEDED')");
            assertThatThrownBy(() -> fixture.execute("failure", body, 0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertEmpty(fixture);
            fixture.jdbc.execute("ALTER TABLE idempotency_command DROP CONSTRAINT synthetic_finalize_failure");
            assertThat(fixture.execute("failure", body, 0).replayed()).isFalse();
            assertThat(fixture.version()).isEqualTo(1);
            assertThat(fixture.count("audit_event")).isEqualTo(1);
        }
    }

    @Test void checksResourcePermissionAgainForReplayAndRejectsRevokedIdentity() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            fixture.execute("permission", Map.of("version", 0), 0);
            fixture.jdbc.update("UPDATE command_test_permission SET enabled = false WHERE actor_id = ?", fixture.user);
            assertThatThrownBy(() -> fixture.execute("permission", Map.of("version", 0), 0)).isInstanceOf(AccessDeniedException.class);
            fixture.jdbc.update("UPDATE command_test_permission SET enabled = true WHERE actor_id = ?", fixture.user);
            // Deliberately make initial command permission pass, then deny the saved-resource check.
            var guarded = fixture.work(fixture.hospital, fixture.resource, 0, () -> { }, () -> { });
            var replayDenied = new IdempotentCommands.Work() {
                @Override public void authorize(CurrentActor.Actor actor) { guarded.authorize(actor); }
                @Override public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) { throw new AssertionError("Must replay"); }
                @Override public void authorizeReplay(CurrentActor.Actor actor, CommandReceipt receipt) { throw new AccessDeniedException("Synthetic resource moved"); }
            };
            assertThatThrownBy(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "permission", Map.of("version", 0), replayDenied)))
                .isInstanceOf(AccessDeniedException.class);
            fixture.jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", fixture.user);
            assertThatThrownBy(() -> fixture.execute("permission", Map.of("version", 0), 0)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            assertThat(fixture.count("audit_event")).isEqualTo(1);
            assertThat(fixture.version()).isEqualTo(1);
        }
    }

    @Test void isolatesActorsHospitalsAndOperationsAndNeverAcceptsActorFromTheCommand() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            var secondId = UUID.randomUUID();
            var second = fixture.addActor(secondId, "synthetic-command-second", fixture.hospital, fixture.resource);
            fixture.execute("shared-key", Map.of("actor", secondId, "version", 0), 0);
            assertThat(fixture.jdbc.queryForObject("SELECT actor_user_id FROM audit_event", UUID.class)).isEqualTo(fixture.user);
            request(second, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "shared-key", Map.of("version", 1),
                fixture.work(fixture.hospital, fixture.resource, 1, () -> { }, () -> { })));
            request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "OTHER_CHANGE_V1", "shared-key", Map.of("version", 2),
                fixture.work(fixture.hospital, fixture.resource, 2, () -> { }, () -> { })));
            var hospital = UUID.randomUUID(); var resource = UUID.randomUUID();
            fixture.jdbc.update("INSERT INTO hospital(id, code, name) VALUES (?, 'command-second', 'Synthetic second hospital')", hospital);
            fixture.jdbc.update("INSERT INTO command_test_resource(id, hospital_id) VALUES (?, ?)", resource, hospital);
            fixture.jdbc.update("INSERT INTO command_test_permission(actor_id, hospital_id, resource_id) VALUES (?, ?, ?)", fixture.user, hospital, resource);
            request(fixture.principal, () -> fixture.commands.execute(hospital, "TEST_CHANGE_V1", "shared-key", Map.of("version", 0),
                fixture.work(hospital, resource, 0, () -> { }, () -> { })));
            assertThat(fixture.count("idempotency_command")).isEqualTo(4);
            assertThat(fixture.count("audit_event")).isEqualTo(4);
        }
    }

    @Test void concurrentIdenticalCommandsReplayAfterTheWinningTransactionCommits() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (var fixture = new CommandTestFixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var body = Map.of("version", 0);
                var winner = executor.submit(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "concurrent", body,
                    fixture.work(fixture.hospital, fixture.resource, 0, () -> { entered.countDown(); await(release); }, () -> { }))));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var contender = executor.submit(() -> fixture.execute("concurrent", body, 0));
                fixture.awaitBlockedReservation();
                release.countDown();
                assertThat(winner.get(8, TimeUnit.SECONDS).replayed()).isFalse();
                assertThat(contender.get(8, TimeUnit.SECONDS).replayed()).isTrue();
                assertThat(fixture.version()).isEqualTo(1);
                assertThat(fixture.count("audit_event")).isEqualTo(1);
                assertThat(fixture.count("idempotency_command")).isEqualTo(1);
            }
        });
    }

    @Test void concurrentRetryCanAcquireTheSameKeyAfterTheFirstTransactionRollsBack() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (var fixture = new CommandTestFixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var body = Map.of("version", 0);
                var failed = executor.submit(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "retry", body,
                    fixture.work(fixture.hospital, fixture.resource, 0, () -> { entered.countDown(); await(release); }, () -> { throw new IllegalStateException("Synthetic rollback"); }))));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var retry = executor.submit(() -> fixture.execute("retry", body, 0));
                fixture.awaitBlockedReservation();
                release.countDown();
                assertThatThrownBy(() -> failed.get(8, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(retry.get(8, TimeUnit.SECONDS).replayed()).isFalse();
                assertThat(fixture.version()).isEqualTo(1);
                assertThat(fixture.count("audit_event")).isEqualTo(1);
                assertThat(fixture.count("idempotency_command")).isEqualTo(1);
            }
        });
    }

    @Test void transactionDeadlineAndPostgresStatementTimeoutRollbackEverythingAndAllowRetry() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            var body = Map.of("version", 0);
            Runnable expireDeadline = () -> {
                var holder = (org.springframework.jdbc.datasource.ConnectionHolder)
                    org.springframework.transaction.support.TransactionSynchronizationManager.getResource(fixture.source);
                holder.setTimeoutInMillis(0);
            };
            Runnable cancelStatement = () -> {
                fixture.jdbc.execute("SET LOCAL statement_timeout = '1ms'");
                fixture.jdbc.execute("SELECT pg_sleep(0.05)");
            };
            for (var failure : java.util.List.of(expireDeadline, cancelStatement)) {
                assertThatThrownBy(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "timeout", body,
                    fixture.work(fixture.hospital, fixture.resource, 0, () -> { }, failure))))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.code()).isEqualTo("COMMAND_TIMEOUT");
                        assertThat(error.status().value()).isEqualTo(503);
                    });
                assertEmpty(fixture);
            }
            assertThat(fixture.execute("timeout", body, 0).replayed()).isFalse();
            assertThat(fixture.version()).isEqualTo(1);
        }
    }

    @Test void boundedUniqueKeyWaitRollsBackAndSameKeyCanReplayAfterTheWinnerCompletes() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (var fixture = new CommandTestFixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var body = Map.of("version", 0);
                var winner = executor.submit(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "busy", body,
                    fixture.work(fixture.hospital, fixture.resource, 0, () -> { entered.countDown(); await(release); }, () -> { }))));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    var contender = executor.submit(() -> fixture.execute("busy", body, 0));
                    fixture.awaitBlockedReservation();
                    assertThatThrownBy(() -> contender.get(5, TimeUnit.SECONDS))
                        .cause().isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("COMMAND_BUSY"));
                    assertEmpty(fixture); // Winner has not committed; loser left no partial history.
                } finally {
                    release.countDown();
                }
                assertThat(winner.get(8, TimeUnit.SECONDS).replayed()).isFalse();
                assertThat(fixture.execute("busy", body, 0).replayed()).isTrue();
                assertThat(fixture.version()).isEqualTo(1);
                assertThat(fixture.count("audit_event")).isEqualTo(1);
                assertThat(fixture.count("idempotency_command")).isEqualTo(1);
            }
        });
    }

    @Test void newAuthenticationVersionDoesNotCreateAnotherIdempotencyNamespace() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            var body = Map.of("version", 0);
            fixture.execute("across-logins", body, 0);
            fixture.jdbc.update("UPDATE app_user SET auth_version = auth_version + 1 WHERE id = ?", fixture.user);
            var refreshed = new com.pis.security.PisPrincipal(new com.pis.security.AccountRepository(fixture.jdbc)
                .findByUsername(fixture.principal.getUsername()).orElseThrow());
            assertThat(refreshed.authVersion()).isEqualTo(1);
            var replay = request(refreshed, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "across-logins", body,
                fixture.work(fixture.hospital, fixture.resource, 0, () -> { }, () -> { })));
            assertThat(replay.replayed()).isTrue();
            assertThat(fixture.version()).isEqualTo(1);
            assertThat(fixture.count("audit_event")).isEqualTo(1);
            assertThat(fixture.count("idempotency_command")).isEqualTo(1);
        }
    }

    @Test void permissionRevocationCommittedByWinnerIsRecheckedAfterTheUniqueKeyWait() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (var fixture = new CommandTestFixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var body = Map.of("version", 0);
                var winner = executor.submit(() -> request(fixture.principal, () -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "revoke-after-wait", body,
                    fixture.work(fixture.hospital, fixture.resource, 0, () -> { entered.countDown(); await(release); },
                        () -> fixture.jdbc.update("UPDATE command_test_permission SET enabled = false WHERE actor_id = ?", fixture.user)))));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var contender = executor.submit(() -> fixture.execute("revoke-after-wait", body, 0));
                fixture.awaitBlockedReservation();
                release.countDown();
                assertThat(winner.get(8, TimeUnit.SECONDS).replayed()).isFalse();
                assertThatThrownBy(() -> contender.get(8, TimeUnit.SECONDS)).hasCauseInstanceOf(AccessDeniedException.class);
                assertThat(fixture.version()).isEqualTo(1);
                assertThat(fixture.count("audit_event")).isEqualTo(1);
                assertThat(fixture.count("idempotency_command")).isEqualTo(1);
            }
        });
    }

    @Test void prohibitsAuditWithoutBusinessTransactionAndCommandsWithoutServerAuthentication() throws Exception {
        try (var fixture = new CommandTestFixture()) {
            assertThatThrownBy(() -> request(fixture.principal, () -> fixture.audit.append(fixture.hospital, "TEST_CHANGE_V1", "TEST_RESOURCE", fixture.resource, 0L, 1)))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> fixture.commands.execute(fixture.hospital, "TEST_CHANGE_V1", "no-user", Map.of("version", 0),
                fixture.work(fixture.hospital, fixture.resource, 0, () -> { }, () -> { }))).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            assertEmpty(fixture);
        }
    }

    private static void assertEmpty(CommandTestFixture fixture) {
        assertThat(fixture.version()).isZero();
        assertThat(fixture.count("audit_event")).isZero();
        assertThat(fixture.count("idempotency_command")).isZero();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic command barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
