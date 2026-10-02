package com.pis.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Internal, read-only projections for the T05 structural template.
 * Hospital scoping is a data-consistency boundary, not an authorization mechanism.
 * No controller exposes these queries; authenticated business services belong to later tasks.
 */
@Repository
public class CoreModelQueries {
    private final JdbcTemplate jdbc;

    public CoreModelQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PatientRecord> findPatient(UUID hospitalId, UUID id) {
        return find("SELECT * FROM patient WHERE hospital_id = ? AND id = ?",
            (row, index) -> new PatientRecord(metadata(row), row.getString("display_name"),
                row.getObject("birth_date", LocalDate.class)), hospitalId, id);
    }

    public Optional<EncounterRecord> findEncounter(UUID hospitalId, UUID id) {
        return find("SELECT * FROM encounter WHERE hospital_id = ? AND id = ?",
            (row, index) -> new EncounterRecord(metadata(row), uuid(row, "patient_id"),
                uuid(row, "source_system_id"), row.getString("encounter_number"),
                uuid(row, "department_id"), instant(row, "occurred_at")), hospitalId, id);
    }

    public Optional<RequestRecord> findRequest(UUID hospitalId, UUID id) {
        return find("SELECT * FROM pathology_request WHERE hospital_id = ? AND id = ?",
            (row, index) -> new RequestRecord(metadata(row), uuid(row, "patient_id"),
                uuid(row, "encounter_id"), uuid(row, "source_system_id"), row.getString("request_number"),
                uuid(row, "requesting_department_id"), instant(row, "requested_at")), hospitalId, id);
    }

    public Optional<CaseRecord> findCase(UUID hospitalId, UUID id) {
        return find("SELECT * FROM pathology_case WHERE hospital_id = ? AND id = ?",
            (row, index) -> new CaseRecord(metadata(row), uuid(row, "request_id"),
                row.getString("number_namespace"), row.getString("case_number")), hospitalId, id);
    }

    public List<ContainerRecord> listContainersForRequest(UUID hospitalId, UUID requestId) {
        return jdbc.query("""
            SELECT * FROM specimen_container WHERE hospital_id = ? AND request_id = ?
            ORDER BY created_at, id
            """, (row, index) -> new ContainerRecord(metadata(row), uuid(row, "request_id"),
                uuid(row, "case_id"), row.getString("number_namespace"), row.getString("container_number"),
                row.getString("label"), instant(row, "collected_at"), instant(row, "received_at")),
            hospitalId, requestId);
    }

    private <T> Optional<T> find(String sql, RowMapper<T> mapper, UUID hospitalId, UUID id) {
        return jdbc.query(sql, mapper, hospitalId, id).stream().findFirst();
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static RowMetadata metadata(ResultSet row) throws SQLException {
        return new RowMetadata(uuid(row, "id"), uuid(row, "hospital_id"), row.getLong("version"),
            instant(row, "created_at"), instant(row, "updated_at"));
    }

    public record RowMetadata(UUID id, UUID hospitalId, long version, Instant createdAt, Instant updatedAt) { }
    public record PatientRecord(RowMetadata metadata, String displayName, LocalDate birthDate) { }
    public record EncounterRecord(RowMetadata metadata, UUID patientId, UUID sourceSystemId,
                                  String encounterNumber, UUID departmentId, Instant occurredAt) { }
    public record RequestRecord(RowMetadata metadata, UUID patientId, UUID encounterId, UUID sourceSystemId,
                                String requestNumber, UUID requestingDepartmentId, Instant requestedAt) { }
    public record CaseRecord(RowMetadata metadata, UUID requestId, String numberNamespace, String caseNumber) { }
    public record ContainerRecord(RowMetadata metadata, UUID requestId, UUID caseId, String numberNamespace,
                                  String containerNumber, String label, Instant collectedAt, Instant receivedAt) { }
}
