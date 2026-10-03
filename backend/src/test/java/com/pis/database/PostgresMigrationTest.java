package com.pis.database;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresMigrationTest {
    @Test
    void initializesAnEmptySchemaAndIsRepeatableWithoutReapplyingMigrations() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var flyway = database.configuration("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(27);
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("27");
            // Explicit production baseline: a new migration requires deliberate contract review.
            assertThat(java.util.Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null)
                .map(migration -> migration.getVersion().toString()).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 27)
                    .mapToObj(Integer::toString).toList());
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = database.connection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);
        }
    }

    @Test
    void versionTwentySixAddsOnlyStorageMetadataAndPreservesVersionTwentyFiveData() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var previous=database.configuration("classpath:db/migration").target("25").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(25);
            try(var connection=database.connection();var statement=connection.createStatement()) {
                statement.executeUpdate("INSERT INTO hospital(id,code,name) VALUES('11111111-1111-4111-8111-111111111128','SYN-T28','Synthetic upgrade preservation')");
            }
            var latest=database.configuration("classpath:db/migration").target("26").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("26");
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();assertThat(latest.migrate().migrationsExecuted).isZero();
            try(var connection=database.connection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT name,(SELECT count(*) FROM storage_version) AS originals FROM hospital WHERE code='SYN-T28'")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString("name")).isEqualTo("Synthetic upgrade preservation");assertThat(rows.getLong("originals")).isZero();assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void upgradesSyntheticVersionOneAndPreservesExistingData() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var versionOne = database.configuration("classpath:db/upgrade-fixture").target("1").load();
            assertThat(versionOne.migrate().migrationsExecuted).isEqualTo(1);
            try (var connection = database.connection(); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO migration_probe (id, value) VALUES (1, 'synthetic-only')");
            }
            var latest = database.configuration("classpath:db/upgrade-fixture").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("2");
            try (var connection = database.connection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT value, revision FROM migration_probe WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("value")).isEqualTo("synthetic-only");
                assertThat(rows.getInt("revision")).isEqualTo(1);
                assertThat(rows.next()).isFalse();
            }
            assertThat(latest.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void rejectsAnAlteredAppliedMigration(@TempDir Path directory) throws Exception {
        var migration = directory.resolve("V1__checksum_probe.sql");
        Files.writeString(migration, "CREATE TABLE checksum_probe (id bigint PRIMARY KEY);\n");
        try (var database = new PostgresTestDatabase()) {
            var flyway = database.configuration("filesystem:" + directory).load();
            flyway.migrate();
            Files.writeString(migration, "CREATE TABLE checksum_probe (id bigint PRIMARY KEY, changed text);\n");
            var changed = database.configuration("filesystem:" + directory).load();
            assertThat(changed.validateWithResult().validationSuccessful).isFalse();
            assertThatThrownBy(changed::migrate).isInstanceOf(FlywayException.class);
        }
    }

    @Test
    void rollsBackFailingPostgresDdl() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var flyway = database.configuration("classpath:db/invalid-fixture").load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
            try (var connection = database.connection();
                 var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
                statement.setString(1, database.schema() + ".must_not_survive");
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNull();
                }
            }
        }
    }

    @Test
    void refusesToBaselineAnUnmanagedNonEmptySchema() throws Exception {
        try (var database = new PostgresTestDatabase();
             var connection = database.connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + database.schema() + "\"");
            statement.execute("CREATE TABLE unmanaged_probe (id bigint)");
            var flyway = database.configuration("classpath:db/migration").load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty");
        }
    }
}
