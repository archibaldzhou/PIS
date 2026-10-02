package com.pis.audit;

import com.pis.database.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditPrivilegesTest {
    @Test void temporaryUnprivilegedRuntimeCanAppendButCannotReadAlterOrEraseAuditHistory() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            database.configuration("classpath:db/migration").load().migrate();
            String role = "pis_test_audit_" + UUID.randomUUID().toString().replace("-", "");
            UUID user = UUID.randomUUID(), hospital = UUID.randomUUID();
            try (var admin = database.connection(); var setup = admin.createStatement()) {
                assertThat(admin.getCatalog()).endsWith("_test");
                setup.execute("CREATE ROLE " + quote(role) + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
                try {
                    setup.execute("GRANT USAGE ON SCHEMA " + quote(database.schema()) + " TO " + quote(role));
                    setup.execute("GRANT INSERT ON audit_event TO " + quote(role));
                    setup.execute("INSERT INTO hospital(id, code, name) VALUES ('" + hospital + "', 'audit-privileges', 'Synthetic audit hospital')");
                    setup.execute("INSERT INTO app_user(id, username, display_name, password_hash) VALUES ('" + user + "', 'synthetic-audit-user', 'Synthetic audit user', 'unused-synthetic-hash')");
                    String owner;
                    try (var rows = setup.executeQuery("SELECT session_user")) { rows.next(); owner = rows.getString(1); }
                    // NOLOGIN avoids creating any password or persistent access. This connection impersonates
                    // actual PostgreSQL runtime privileges; it is not evidence of production account provisioning.
                    try (var restricted = database.connection(); var runtime = restricted.createStatement()) {
                        runtime.execute("SET SESSION AUTHORIZATION " + quote(role));
                        try (var rows = runtime.executeQuery("SELECT current_user")) { rows.next(); assertThat(rows.getString(1)).isEqualTo(role); }
                        runtime.executeUpdate("INSERT INTO audit_event(id, actor_user_id, actor_auth_version, hospital_id, operation_code, resource_type, resource_id, result_version, trace_id) VALUES ('"
                            + UUID.randomUUID() + "', '" + user + "', 0, '" + hospital + "', 'TEST_CHANGE_V1', 'TEST_RESOURCE', '" + UUID.randomUUID() + "', 1, '" + UUID.randomUUID() + "')");
                        for (String denied : List.of(
                            "SELECT * FROM audit_event",
                            "UPDATE audit_event SET operation_code = 'TAMPERED'",
                            "DELETE FROM audit_event", "TRUNCATE audit_event",
                            "ALTER TABLE audit_event ADD COLUMN tampered boolean", "DROP TABLE audit_event",
                            "CREATE TABLE " + quote(database.schema()) + ".unauthorized_table(id integer)",
                            "SET ROLE " + quote(owner),
                            "ALTER ROLE " + quote(role) + " SUPERUSER")) {
                            expectPrivilegeDenied(restricted, denied);
                        }
                    }
                    try (var rows = setup.executeQuery("SELECT count(*) FROM audit_event")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
                } finally {
                    setup.execute("REVOKE ALL ON audit_event FROM " + quote(role));
                    setup.execute("REVOKE ALL ON SCHEMA " + quote(database.schema()) + " FROM " + quote(role));
                    setup.execute("DROP ROLE " + quote(role));
                }
            }
        }
    }

    private static void expectPrivilegeDenied(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            var failure = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertThat(failure.getSQLState()).isEqualTo("42501");
        }
    }
    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
