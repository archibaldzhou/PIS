package com.pis.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CoreModelQueriesTest {
    @Test
    void mapsAllCoreRecordsWithExplicitHospitalScopeAndOptionalValues() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            fixture.execute("UPDATE patient SET display_name = 'Synthetic patient', birth_date = DATE '2000-01-02' WHERE id = ?", graph.patient());
            fixture.execute("UPDATE encounter SET occurred_at = TIMESTAMPTZ '2020-01-02 12:00:00+08' WHERE id = ?", graph.encounter());
            fixture.execute("UPDATE pathology_request SET requested_at = TIMESTAMPTZ '2020-01-02 04:01:00Z' WHERE id = ?", graph.request());
            fixture.execute("UPDATE specimen_container SET label = 'Synthetic container', collected_at = TIMESTAMPTZ '2020-01-02 04:02:00Z', received_at = TIMESTAMPTZ '2020-01-02 04:03:00Z' WHERE id = ?", graph.container());
            var unassigned = fixture.container(graph.hospital(), graph.request(), null, null, null);
            var queries = new CoreModelQueries(fixture.jdbc);
            var patient = queries.findPatient(graph.hospital(), graph.patient()).orElseThrow();
            assertThat(patient.metadata().id()).isEqualTo(graph.patient());
            assertThat(patient.metadata().hospitalId()).isEqualTo(graph.hospital());
            assertThat(patient.metadata().version()).isZero();
            assertThat(patient.metadata().createdAt()).isNotNull();
            assertThat(patient.metadata().updatedAt()).isNotNull();
            assertThat(patient.displayName()).isEqualTo("Synthetic patient");
            assertThat(patient.birthDate()).isEqualTo(LocalDate.of(2000, 1, 2));
            var encounter = queries.findEncounter(graph.hospital(), graph.encounter()).orElseThrow();
            assertThat(encounter.patientId()).isEqualTo(graph.patient());
            assertThat(encounter.sourceSystemId()).isEqualTo(graph.source());
            assertThat(encounter.departmentId()).isEqualTo(graph.department());
            assertThat(encounter.encounterNumber()).isEqualTo("00001");
            assertThat(encounter.occurredAt()).isEqualTo(Instant.parse("2020-01-02T04:00:00Z"));
            var request = queries.findRequest(graph.hospital(), graph.request()).orElseThrow();
            assertThat(request.patientId()).isEqualTo(graph.patient());
            assertThat(request.encounterId()).isEqualTo(graph.encounter());
            assertThat(request.sourceSystemId()).isEqualTo(graph.source());
            assertThat(request.requestNumber()).isEqualTo("00001");
            assertThat(request.requestingDepartmentId()).isEqualTo(graph.department());
            assertThat(request.requestedAt()).isEqualTo(Instant.parse("2020-01-02T04:01:00Z"));
            var pathologyCase = queries.findCase(graph.hospital(), graph.pathologyCase()).orElseThrow();
            assertThat(pathologyCase.requestId()).isEqualTo(graph.request());
            assertThat(pathologyCase.numberNamespace()).isEqualTo("synthetic-case");
            assertThat(pathologyCase.caseNumber()).isEqualTo("00001");
            var containers = queries.listContainersForRequest(graph.hospital(), graph.request());
            assertThat(containers).hasSize(2);
            var numbered = containers.stream().filter(container -> container.metadata().id().equals(graph.container())).findFirst().orElseThrow();
            assertThat(numbered.caseId()).isEqualTo(graph.pathologyCase());
            assertThat(numbered.numberNamespace()).isEqualTo("synthetic-container");
            assertThat(numbered.containerNumber()).isEqualTo("00001");
            assertThat(numbered.label()).isEqualTo("Synthetic container");
            assertThat(numbered.collectedAt()).isEqualTo(Instant.parse("2020-01-02T04:02:00Z"));
            assertThat(numbered.receivedAt()).isEqualTo(Instant.parse("2020-01-02T04:03:00Z"));
            var draft = containers.stream().filter(container -> container.metadata().id().equals(unassigned)).findFirst().orElseThrow();
            assertThat(draft.caseId()).isNull();
            assertThat(draft.numberNamespace()).isNull();
            assertThat(draft.containerNumber()).isNull();
            assertThat(draft.collectedAt()).isNull();
            assertThat(draft.receivedAt()).isNull();
            var missingHospital = UUID.randomUUID();
            assertThat(queries.findPatient(missingHospital, graph.patient())).isEmpty();
            assertThat(queries.findEncounter(missingHospital, graph.encounter())).isEmpty();
            assertThat(queries.findRequest(missingHospital, graph.request())).isEmpty();
            assertThat(queries.findCase(missingHospital, graph.pathologyCase())).isEmpty();
            assertThat(queries.listContainersForRequest(missingHospital, graph.request())).isEmpty();
            assertThat(queries.findPatient(graph.hospital(), UUID.randomUUID())).isEmpty();
            assertThat(queries.findEncounter(graph.hospital(), UUID.randomUUID())).isEmpty();
            assertThat(queries.findRequest(graph.hospital(), UUID.randomUUID())).isEmpty();
            assertThat(queries.findCase(graph.hospital(), UUID.randomUUID())).isEmpty();
            assertThat(queries.listContainersForRequest(graph.hospital(), UUID.randomUUID())).isEmpty();
        }
    }

    @Test
    void mapsRequestWithoutEncounterAndUnnumberedCaseAsStructuralDraftsOnly() throws Exception {
        try (var fixture = new CoreModelTestFixture()) {
            var graph = fixture.graph("synthetic-hospital");
            var requestId = fixture.request(graph.hospital(), graph.patient(), null, graph.source(), "no-encounter", null);
            var caseId = fixture.pathologyCase(graph.hospital(), requestId, null, null);
            var queries = new CoreModelQueries(fixture.jdbc);
            var request = queries.findRequest(graph.hospital(), requestId).orElseThrow();
            assertThat(request.encounterId()).isNull();
            assertThat(request.requestingDepartmentId()).isNull();
            assertThat(request.requestedAt()).isNull();
            var pathologyCase = queries.findCase(graph.hospital(), caseId).orElseThrow();
            assertThat(pathologyCase.caseNumber()).isNull();
            assertThat(pathologyCase.numberNamespace()).isNull();
        }
    }
}
