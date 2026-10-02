package com.pis.core;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CoreModelVersionTest {
    @Test
    void staleCompareAndSetFromIndependentConnectionCannotOverwriteTheWinner() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            try (var first = fixture.database.connection(); var second = fixture.database.connection()) {
                // Both independent connections read the same revision before either writes.
                long firstVersion = readVersion(first, graph.patient());
                long secondVersion = readVersion(second, graph.patient());
                assertThat(firstVersion).isEqualTo(secondVersion).isZero();
                var update = """
                    UPDATE patient SET display_name = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE hospital_id = ? AND id = ? AND version = ?
                    """;
                assertThat(CoreModelTestFixture.execute(first, update, "Synthetic winner", graph.hospital(), graph.patient(), firstVersion)).isEqualTo(1);
                assertThat(CoreModelTestFixture.execute(second, update, "Synthetic stale value", graph.hospital(), graph.patient(), secondVersion)).isZero();
                assertThat(CoreModelTestFixture.execute(second, update, "Wrong scope", UUID.randomUUID(), graph.patient(), firstVersion + 1)).isZero();
                var patient = new CoreModelQueries(fixture.jdbc).findPatient(graph.hospital(), graph.patient()).orElseThrow();
                assertThat(patient.displayName()).isEqualTo("Synthetic winner");
                assertThat(patient.metadata().version()).isEqualTo(1);
                assertThat(patient.metadata().updatedAt()).isAfterOrEqualTo(patient.metadata().createdAt());
            }
        }
    }

    private static long readVersion(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT version FROM patient WHERE id = ?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }
}
