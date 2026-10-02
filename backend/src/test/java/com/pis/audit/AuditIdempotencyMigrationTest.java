package com.pis.audit;

import com.pis.database.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuditIdempotencyMigrationTest {
    @Test void upgradesProductionV3ToV4WithoutChangingExistingHistoryOrSyntheticRecords() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var baseline = database.configuration("classpath:db/migration").target("3").load();
            assertThat(baseline.migrate().migrationsExecuted).isEqualTo(3);
            String baselineChecksum = baseline.info().current().getChecksum().toString();
            try (var connection = database.connection(); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO hospital(code, name) VALUES ('t07-upgrade', 'Synthetic retained hospital')");
            }
            var latest = database.configuration("classpath:db/migration").target("4").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("4");
            assertThat(latest.migrate().migrationsExecuted).isZero();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            try (var connection = database.connection(); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '3'")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo(baselineChecksum);
                }
                try (var rows = statement.executeQuery("SELECT name FROM hospital WHERE code = 't07-upgrade'")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("Synthetic retained hospital");
                }
                for (String table : new String[] {"audit_event", "idempotency_command"}) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
                }
            }
        }
    }
}
