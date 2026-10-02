package com.pis.security.access;

import com.pis.database.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Disposable synthetic records only; each instance owns an isolated real PostgreSQL 17 schema. */
final class AccessPolicyTestFixture implements AutoCloseable {
    final PostgresTestDatabase database = new PostgresTestDatabase();
    final Connection connection;
    final JdbcTemplate jdbc;
    final CaseAccessPolicy policy;

    AccessPolicyTestFixture() throws SQLException {
        database.configuration("classpath:db/migration").load().migrate();
        connection = database.connection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        policy = new CaseAccessPolicy(jdbc);
    }

    UUID insert(String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql + " RETURNING id")) {
            bind(statement, arguments);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    int execute(String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            return statement.executeUpdate();
        }
    }

    private static void bind(java.sql.PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    UUID user() throws SQLException {
        return insert("""
            INSERT INTO app_user (username, display_name, password_hash, enabled, synthetic_only)
            VALUES (?, 'Synthetic test user', 'synthetic-test-hash-not-for-login', true, true)
            """, "synthetic-" + UUID.randomUUID());
    }

    Graph graph() throws SQLException {
        var hospital = insert("INSERT INTO hospital (code, name) VALUES (?, 'Synthetic hospital')", UUID.randomUUID().toString());
        var campus = campus(hospital);
        var department = department(hospital, campus);
        var source = insert("INSERT INTO source_system (hospital_id, code, name) VALUES (?, 'source', 'Synthetic source')", hospital);
        var patient = insert("INSERT INTO patient (hospital_id) VALUES (?)", hospital);
        var request = insert("""
            INSERT INTO pathology_request (hospital_id, patient_id, source_system_id, request_number, requesting_department_id)
            VALUES (?, ?, ?, 'synthetic-request', ?)
            """, hospital, patient, source, department);
        var pathologyCase = pathologyCase(hospital, request);
        scope(hospital, pathologyCase, campus, department);
        return new Graph(hospital, campus, department, request, pathologyCase);
    }

    UUID campus(UUID hospital) throws SQLException {
        return insert("INSERT INTO campus (hospital_id, code, name) VALUES (?, ?, 'Synthetic campus')", hospital, UUID.randomUUID().toString());
    }

    UUID department(UUID hospital, UUID campus) throws SQLException {
        var department = insert("INSERT INTO department (hospital_id, code, name) VALUES (?, ?, 'Synthetic department')", hospital, UUID.randomUUID().toString());
        map(hospital, campus, department);
        return department;
    }

    void map(UUID hospital, UUID campus, UUID department) throws SQLException {
        execute("INSERT INTO department_campus (hospital_id, campus_id, department_id) VALUES (?, ?, ?)", hospital, campus, department);
    }

    UUID pathologyCase(UUID hospital, UUID request) throws SQLException {
        return insert("INSERT INTO pathology_case (hospital_id, request_id) VALUES (?, ?)", hospital, request);
    }

    UUID anotherCase(Graph graph, UUID campus, UUID department) throws SQLException {
        var id = pathologyCase(graph.hospital(), graph.request());
        scope(graph.hospital(), id, campus, department);
        return id;
    }

    void scope(UUID hospital, UUID pathologyCase, UUID campus, UUID department) throws SQLException {
        execute("""
            INSERT INTO case_access_scope (hospital_id, case_id, campus_id, owning_department_id)
            VALUES (?, ?, ?, ?)
            """, hospital, pathologyCase, campus, department);
    }

    UUID grant(UUID user, String role, UUID hospital, String kind, UUID campus, UUID department, String filter) throws SQLException {
        return insert("""
            INSERT INTO user_role_scope (user_id, role_code, hospital_id, scope_kind, campus_id, department_id, case_filter)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, user, role, hospital, kind, campus, department, filter);
    }

    UUID qualification(UUID user, UUID hospital, String kind, UUID campus, UUID department) throws SQLException {
        return insert("""
            INSERT INTO user_operation_qualification (user_id, operation_code, hospital_id, scope_kind, campus_id, department_id)
            VALUES (?, 'CASE_EDIT', ?, ?, ?, ?)
            """, user, hospital, kind, campus, department);
    }

    UUID assignment(UUID user, Graph graph, String access, long version) throws SQLException {
        return insert("""
            INSERT INTO case_assignment (hospital_id, case_id, user_id, access_level, scope_version)
            VALUES (?, ?, ?, ?, ?)
            """, graph.hospital(), graph.pathologyCase(), user, access, version);
    }

    long authVersion(UUID user) {
        return jdbc.queryForObject("SELECT auth_version FROM app_user WHERE id = ?", Long.class, user);
    }

    @Override
    public void close() throws SQLException {
        try {
            connection.close();
        } finally {
            database.close();
        }
    }

    record Graph(UUID hospital, UUID campus, UUID department, UUID request, UUID pathologyCase) { }
}
