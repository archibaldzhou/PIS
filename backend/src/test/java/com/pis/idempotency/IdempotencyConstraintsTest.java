package com.pis.idempotency;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyConstraintsTest {
    @Test void rejectsNullSuccessFieldsHalfCompleteReservationsAndInvalidHashes() throws Exception {
        try (var fixture = new CommandTestFixture(); var connection = fixture.database.connection()) {
            for (String invalidTail : List.of(
                    "'SUCCEEDED', NULL, 'TEST_RESOURCE', '" + fixture.resource + "', 1, statement_timestamp()",
                    "'SUCCEEDED', 200, NULL, '" + fixture.resource + "', 1, statement_timestamp()",
                    "'SUCCEEDED', 200, 'TEST_RESOURCE', NULL, 1, statement_timestamp()",
                    "'SUCCEEDED', 200, 'TEST_RESOURCE', '" + fixture.resource + "', NULL, statement_timestamp()",
                    "'SUCCEEDED', 200, 'TEST_RESOURCE', '" + fixture.resource + "', 1, NULL",
                    "'IN_PROGRESS', 200, NULL, NULL, NULL, NULL")) {
                try (var statement = connection.createStatement()) {
                    String sql = "INSERT INTO idempotency_command(id, actor_user_id, hospital_id, operation_code, key_hash, request_digest, digest_version, state, response_status, resource_type, resource_id, result_version, completed_at) VALUES ('"
                        + UUID.randomUUID() + "', '" + fixture.user + "', '" + fixture.hospital
                        + "', 'TEST_CHANGE_V1', decode(repeat('00',32),'hex'), decode(repeat('11',32),'hex'), 1, " + invalidTail + ")";
                    var failure = assertThrows(SQLException.class, () -> statement.execute(sql));
                    assertThat(failure.getSQLState()).isEqualTo("23514");
                }
            }
            assertThat(fixture.count("idempotency_command")).isZero();
        }
    }
}
