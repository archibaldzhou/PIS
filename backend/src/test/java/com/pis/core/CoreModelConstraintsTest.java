package com.pis.core;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreModelConstraintsTest {
    @Test
    void supportsSeparateEntitiesMultipleCasesAndContainersAsAStructuralTemplate() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            assertThat(Set.of(graph.hospital(), graph.source(), graph.department(), graph.patient(),
                graph.identifier(), graph.encounter(), graph.request(), graph.pathologyCase(), graph.container())).hasSize(9);
            var secondCase = fixture.pathologyCase(graph.hospital(), graph.request(), "synthetic-case", "00002");
            fixture.container(graph.hospital(), graph.request(), secondCase, "synthetic-container", "00002");
            fixture.container(graph.hospital(), graph.request(), null, null, null);
            fixture.container(graph.hospital(), graph.request(), null, null, null);
            fixture.pathologyCase(graph.hospital(), graph.request(), null, null);
            fixture.pathologyCase(graph.hospital(), graph.request(), null, null);
            fixture.request(graph.hospital(), graph.patient(), null, graph.source(), "without-encounter", null);
            assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM pathology_case", Integer.class)).isEqualTo(4);
            assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM specimen_container", Integer.class)).isEqualTo(4);
            // Unknown identity remains unknown; no clinical or demographic default is invented.
            assertThat(fixture.jdbc.queryForObject("SELECT display_name FROM patient WHERE id = ?", String.class, graph.patient())).isNull();
            assertThat(fixture.jdbc.queryForObject("SELECT birth_date FROM patient WHERE id = ?", java.sql.Date.class, graph.patient())).isNull();
        }
    }

    @Test
    void rejectsDuplicateBusinessIdentifiersWithinTheirExplicitScope() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            expectSqlState("23505", () -> fixture.insert("INSERT INTO hospital (code, name) VALUES ('synthetic-hospital', 'duplicate')"));
            expectSqlState("23505", () -> fixture.source(graph.hospital(), "synthetic-source"));
            expectSqlState("23505", () -> fixture.insert("INSERT INTO department (hospital_id, code, name) VALUES (?, 'D01', 'duplicate')", graph.hospital()));
            var otherPatient = fixture.patient(graph.hospital());
            expectSqlState("23505", () -> fixture.insert("""
                INSERT INTO patient_identifier (hospital_id, patient_id, source_system_id, identifier_namespace, identifier_value)
                VALUES (?, ?, ?, 'synthetic-mrn', '00001')
                """, graph.hospital(), otherPatient, graph.source()));
            expectSqlState("23505", () -> fixture.encounter(graph.hospital(), otherPatient, graph.source(), "00001", null));
            expectSqlState("23505", () -> fixture.request(graph.hospital(), otherPatient, null, graph.source(), "00001", null));
            expectSqlState("23505", () -> fixture.pathologyCase(graph.hospital(), graph.request(), "synthetic-case", "00001"));
            expectSqlState("23505", () -> fixture.container(graph.hospital(), graph.request(), null, "synthetic-container", "00001"));
        }
    }

    @Test
    void permitsRepeatedExternalTextAcrossHospitalsSourcesAndNamespaces() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital-a");
            fixture.graph("synthetic-hospital-b");
            var source = fixture.source(graph.hospital(), "another-source");
            fixture.encounter(graph.hospital(), graph.patient(), source, "00001", null);
            fixture.request(graph.hospital(), graph.patient(), null, source, "00001", null);
            fixture.insert("""
                INSERT INTO patient_identifier (hospital_id, patient_id, source_system_id, identifier_namespace, identifier_value)
                VALUES (?, ?, ?, 'synthetic-mrn', '00001'), (?, ?, ?, 'other-namespace', '00001')
                """, graph.hospital(), graph.patient(), source, graph.hospital(), graph.patient(), graph.source());
            fixture.pathologyCase(graph.hospital(), graph.request(), "other-namespace", "00001");
            fixture.container(graph.hospital(), graph.request(), null, "other-namespace", "00001");
            fixture.pathologyCase(graph.hospital(), graph.request(), "synthetic-case", "Case-A");
            fixture.pathologyCase(graph.hospital(), graph.request(), "synthetic-case", "case-a");
            assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM hospital", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void rejectsCrossHospitalParentsSourcesDepartmentsAndChildLinks() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var a = fixture.graph("synthetic-hospital-a");
            var b = fixture.graph("synthetic-hospital-b");
            expectSqlState("23503", () -> fixture.insert("""
                INSERT INTO patient_identifier (hospital_id, patient_id, source_system_id, identifier_namespace, identifier_value)
                VALUES (?, ?, ?, 'new', 'new')
                """, a.hospital(), b.patient(), a.source()));
            expectSqlState("23503", () -> fixture.insert("""
                INSERT INTO patient_identifier (hospital_id, patient_id, source_system_id, identifier_namespace, identifier_value)
                VALUES (?, ?, ?, 'new', 'new')
                """, a.hospital(), a.patient(), b.source()));
            expectSqlState("23503", () -> fixture.encounter(a.hospital(), b.patient(), a.source(), "new", null));
            expectSqlState("23503", () -> fixture.encounter(a.hospital(), a.patient(), b.source(), "new", null));
            expectSqlState("23503", () -> fixture.encounter(a.hospital(), a.patient(), a.source(), "new", b.department()));
            expectSqlState("23503", () -> fixture.request(a.hospital(), b.patient(), null, a.source(), "new", null));
            expectSqlState("23503", () -> fixture.request(a.hospital(), a.patient(), b.encounter(), a.source(), "new", null));
            expectSqlState("23503", () -> fixture.request(a.hospital(), a.patient(), null, b.source(), "new", null));
            expectSqlState("23503", () -> fixture.request(a.hospital(), a.patient(), null, a.source(), "new", b.department()));
            expectSqlState("23503", () -> fixture.pathologyCase(a.hospital(), b.request(), null, null));
            expectSqlState("23503", () -> fixture.container(a.hospital(), b.request(), null, null, null));
            expectSqlState("23503", () -> fixture.container(a.hospital(), a.request(), b.pathologyCase(), null, null));
        }
    }

    @Test
    void rejectsWrongPatientEncounterAndWrongRequestCaseEvenWithinOneHospital() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            var otherPatient = fixture.patient(graph.hospital());
            expectSqlState("23503", () -> fixture.request(graph.hospital(), otherPatient, graph.encounter(), graph.source(), "new", null));
            var otherRequest = fixture.request(graph.hospital(), graph.patient(), null, graph.source(), "other", null);
            expectSqlState("23503", () -> fixture.container(graph.hospital(), otherRequest, graph.pathologyCase(), null, null));
            // Constraints protect updates as well as insertion.
            expectSqlState("23503", () -> fixture.execute("UPDATE pathology_request SET patient_id = ? WHERE id = ?", otherPatient, graph.request()));
            expectSqlState("23503", () -> fixture.execute("UPDATE specimen_container SET request_id = ? WHERE id = ?", otherRequest, graph.container()));
        }
    }

    @Test
    void rejectsMissingParentsAndRequiredRelationships() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            var absent = UUID.randomUUID();
            expectSqlState("23503", () -> fixture.patient(absent));
            expectSqlState("23503", () -> fixture.encounter(graph.hospital(), absent, graph.source(), "new", null));
            expectSqlState("23503", () -> fixture.request(graph.hospital(), graph.patient(), absent, graph.source(), "new", null));
            expectSqlState("23503", () -> fixture.pathologyCase(graph.hospital(), absent, null, null));
            expectSqlState("23503", () -> fixture.container(graph.hospital(), graph.request(), absent, null, null));
            expectSqlState("23502", () -> fixture.patient(null));
            expectSqlState("23502", () -> fixture.encounter(graph.hospital(), null, graph.source(), "new", null));
            expectSqlState("23502", () -> fixture.request(graph.hospital(), null, null, graph.source(), "new", null));
            expectSqlState("23502", () -> fixture.pathologyCase(graph.hospital(), null, null, null));
            expectSqlState("23502", () -> fixture.container(graph.hospital(), null, null, null, null));
        }
    }

    @Test
    void rejectsDeletingReferencedRowsWithoutCascadingClinicalStructure() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            fixture.graph("synthetic-hospital");
            for (var table : List.of("hospital", "source_system", "department", "patient", "encounter", "pathology_request", "pathology_case")) {
                expectSqlState("23503", () -> fixture.execute("DELETE FROM " + table));
                assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isEqualTo(1);
            }
            assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM specimen_container", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void rejectsBlankUntrimmedAndHalfPresentNumbers() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            for (var value : List.of("", " ", "\t", "\n", " leading", "trailing ")) {
                expectSqlState("23514", () -> fixture.encounter(graph.hospital(), graph.patient(), graph.source(), value, null));
                expectSqlState("23514", () -> fixture.request(graph.hospital(), graph.patient(), null, graph.source(), value, null));
                expectSqlState("23514", () -> fixture.pathologyCase(graph.hospital(), graph.request(), "synthetic-case", value));
                expectSqlState("23514", () -> fixture.container(graph.hospital(), graph.request(), null, "synthetic-container", value));
                expectSqlState("23514", () -> fixture.execute("UPDATE patient_identifier SET identifier_value = ?", value));
                expectSqlState("23514", () -> fixture.execute("UPDATE source_system SET code = ?", value));
                expectSqlState("23514", () -> fixture.execute("UPDATE department SET code = ?", value));
                expectSqlState("23514", () -> fixture.execute("UPDATE hospital SET code = ?", value));
            }
            expectSqlState("23514", () -> fixture.pathologyCase(graph.hospital(), graph.request(), null, "half"));
            expectSqlState("23514", () -> fixture.pathologyCase(graph.hospital(), graph.request(), "half", null));
            expectSqlState("23514", () -> fixture.container(graph.hospital(), graph.request(), null, null, "half"));
            expectSqlState("23514", () -> fixture.container(graph.hospital(), graph.request(), null, "half", null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hospital", "source_system", "department", "patient", "patient_identifier", "encounter", "pathology_request", "pathology_case", "specimen_container"})
    void initializesMetadataAndRejectsNegativeOrNullVersions(String table) throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            fixture.graph("synthetic-hospital");
            var metadata = fixture.jdbc.queryForMap("SELECT id, version, created_at, updated_at FROM " + table);
            assertThat(metadata.get("id")).isInstanceOf(UUID.class);
            assertThat(metadata.get("version")).isEqualTo(0L);
            assertThat(metadata.get("created_at")).isNotNull().isEqualTo(metadata.get("updated_at"));
            expectSqlState("23514", () -> fixture.execute("UPDATE " + table + " SET version = -1"));
            expectSqlState("23502", () -> fixture.execute("UPDATE " + table + " SET version = NULL"));
        }
    }

    @Test
    void correctingBusinessNumbersDoesNotChangeInternalRelationships() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            fixture.execute("UPDATE patient_identifier SET identifier_value = 'corrected-mrn', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", graph.identifier());
            fixture.execute("UPDATE encounter SET encounter_number = 'corrected-encounter', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", graph.encounter());
            fixture.execute("UPDATE pathology_request SET request_number = 'corrected-request', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", graph.request());
            fixture.execute("UPDATE pathology_case SET case_number = 'corrected-case', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", graph.pathologyCase());
            fixture.execute("UPDATE specimen_container SET container_number = 'corrected-container', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", graph.container());
            var queries = new CoreModelQueries(fixture.jdbc);
            assertThat(queries.findRequest(graph.hospital(), graph.request()).orElseThrow().encounterId()).isEqualTo(graph.encounter());
            assertThat(queries.findCase(graph.hospital(), graph.pathologyCase()).orElseThrow().requestId()).isEqualTo(graph.request());
            assertThat(queries.listContainersForRequest(graph.hospital(), graph.request()).getFirst().caseId()).isEqualTo(graph.pathologyCase());
        }
    }

    private static void expectSqlState(String state, Executable action) {
        assertThat(assertThrows(SQLException.class, action).getSQLState()).isEqualTo(state);
    }
}
