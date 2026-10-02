package com.pis.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Each test owns one random schema in a dedicated, disposable PostgreSQL database. */
public final class PostgresTestDatabase implements AutoCloseable {
    private final String url = environment("PIS_TEST_DB_URL",
        "jdbc:postgresql://127.0.0.1:5433/pis_test?connectTimeout=5&socketTimeout=10");
    private final String username = environment("PIS_TEST_DB_USERNAME", "pis_test");
    private final String password = requiredEnvironment("PIS_TEST_DB_PASSWORD");
    private final String schema = "pis_test_" + UUID.randomUUID().toString().replace("-", "");

    public PostgresTestDatabase() {
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("Tests require a real PostgreSQL JDBC URL");
        }
        try (var connection = connection()) {
            var metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName())
                    || metadata.getDatabaseMajorVersion() != 17) {
                throw new IllegalStateException("Tests require PostgreSQL 17");
            }
            if (!connection.getCatalog().endsWith("_test")) {
                throw new IllegalStateException("Use a disposable database whose name ends in _test");
            }
        } catch (SQLException error) {
            throw new IllegalStateException("PostgreSQL test database is unavailable; tests are not skipped", error);
        }
    }

    public Connection connection() throws SQLException {
        var connection = DriverManager.getConnection(url, username, password);
        connection.setSchema(schema);
        return connection;
    }

    public FluentConfiguration configuration(String location) {
        return Flyway.configure().dataSource(url, username, password)
            .schemas(schema).defaultSchema(schema).locations(location)
            .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
            .validateOnMigrate(true).validateMigrationNaming(true).outOfOrder(false);
    }

    public String schema() {
        return schema;
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.hikari.schema", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.flyway.schemas", () -> schema);
    }

    public String[] applicationArguments(String migrationLocation) {
        return new String[] {
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + username,
            "--spring.datasource.password=" + password,
            "--spring.datasource.hikari.schema=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.locations=" + migrationLocation,
            "--spring.main.banner-mode=off"
        };
    }

    @Override
    public void close() throws SQLException {
        // Never use Flyway.clean or drop the database. Only remove this instance's generated schema.
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    private static String environment(String name, String defaultValue) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " for the disposable PostgreSQL test database");
        }
        return value;
    }
}
