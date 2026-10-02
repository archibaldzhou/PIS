package com.pis.database;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CoreModelMigrationTest {
    @Test
    void upgradesRealProductionVersionOneToCoreModelAndPreservesHistoryAndExistingData() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var versionOne = database.configuration("classpath:db/migration").target("1").load();
            assertThat(versionOne.migrate().migrationsExecuted).isEqualTo(1);
            var versionOneChecksum = versionOne.info().current().getChecksum();
            try (var connection = database.connection(); var statement = connection.createStatement()) {
                // V1 has no business table. This marker verifies that V2 is additive, not a reset.
                statement.execute("CREATE TABLE synthetic_upgrade_marker (value text NOT NULL)");
                statement.executeUpdate("INSERT INTO synthetic_upgrade_marker VALUES ('synthetic-preserved')");
            }
            var latest = database.configuration("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("2");
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            try (var connection = database.connection(); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT value FROM synthetic_upgrade_marker")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("synthetic-preserved");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '1' AND success")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(versionOneChecksum);
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()")) {
                    Set<String> tables = new HashSet<>();
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                    assertThat(tables).contains("hospital", "source_system", "department", "patient", "patient_identifier",
                        "encounter", "pathology_request", "pathology_case", "specimen_container");
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM patient")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }
}
