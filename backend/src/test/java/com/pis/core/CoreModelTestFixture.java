package com.pis.core;

import com.pis.database.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Disposable, generated, synthetic-only records; never use this helper with a clinical database. */
final class CoreModelTestFixture implements AutoCloseable {
    final PostgresTestDatabase database = new PostgresTestDatabase();
    final Connection connection;
    final JdbcTemplate jdbc;

    CoreModelTestFixture() throws SQLException {
        database.configuration("classpath:db/migration").load().migrate();
        connection = database.connection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
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
        return execute(connection, sql, arguments);
    }

    static int execute(Connection connection, String sql, Object... arguments) throws SQLException {
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

    Graph graph(String hospitalCode) throws SQLException {
        var hospital = insert("INSERT INTO hospital (code, name) VALUES (?, 'Synthetic hospital')", hospitalCode);
        var source = source(hospital, "synthetic-source");
        var department = insert("INSERT INTO department (hospital_id, code, name) VALUES (?, 'D01', 'Synthetic department')", hospital);
        var patient = patient(hospital);
        var identifier = insert("""
            INSERT INTO patient_identifier (hospital_id, patient_id, source_system_id, identifier_namespace, identifier_value)
            VALUES (?, ?, ?, 'synthetic-mrn', '00001')
            """, hospital, patient, source);
        var encounter = encounter(hospital, patient, source, "00001", department);
        var request = request(hospital, patient, encounter, source, "00001", department);
        var pathologyCase = pathologyCase(hospital, request, "synthetic-case", "00001");
        var container = container(hospital, request, pathologyCase, "synthetic-container", "00001");
        return new Graph(hospital, source, department, patient, identifier, encounter, request, pathologyCase, container);
    }

    UUID source(UUID hospital, String code) throws SQLException {
        return insert("INSERT INTO source_system (hospital_id, code, name) VALUES (?, ?, 'Synthetic source')", hospital, code);
    }

    UUID patient(UUID hospital) throws SQLException {
        return insert("INSERT INTO patient (hospital_id) VALUES (?)", hospital);
    }

    UUID encounter(UUID hospital, UUID patient, UUID source, String number, UUID department) throws SQLException {
        return insert("""
            INSERT INTO encounter (hospital_id, patient_id, source_system_id, encounter_number, department_id)
            VALUES (?, ?, ?, ?, ?)
            """, hospital, patient, source, number, department);
    }

    UUID request(UUID hospital, UUID patient, UUID encounter, UUID source, String number, UUID department) throws SQLException {
        return insert("""
            INSERT INTO pathology_request (hospital_id, patient_id, encounter_id, source_system_id, request_number, requesting_department_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, hospital, patient, encounter, source, number, department);
    }

    UUID pathologyCase(UUID hospital, UUID request, String namespace, String number) throws SQLException {
        return insert("INSERT INTO pathology_case (hospital_id, request_id, number_namespace, case_number) VALUES (?, ?, ?, ?)",
            hospital, request, namespace, number);
    }

    UUID container(UUID hospital, UUID request, UUID pathologyCase, String namespace, String number) throws SQLException {
        return insert("""
            INSERT INTO specimen_container (hospital_id, request_id, case_id, number_namespace, container_number)
            VALUES (?, ?, ?, ?, ?)
            """, hospital, request, pathologyCase, namespace, number);
    }

    @Override
    public void close() throws SQLException {
        try {
            connection.close();
        } finally {
            database.close();
        }
    }

    record Graph(UUID hospital, UUID source, UUID department, UUID patient, UUID identifier, UUID encounter,
                 UUID request, UUID pathologyCase, UUID container) { }
}
