package com.pis.security.access;

import com.pis.database.PostgresTestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class IdentityAccessMigrationTest {
    @Test
    void emptySchemaGetsNineAccessTablesAndOnlyThreeRoleTemplatesWithoutAccountsOrClinicalSeeds() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var flyway = f.database.configuration("classpath:db/migration").load();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("32");
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            var tables = f.jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()", String.class);
            assertThat(tables).contains("campus", "department_campus", "app_user", "security_role", "security_role_permission",
                "user_role_scope", "case_access_scope", "case_assignment", "user_operation_qualification",
                "workflow_scope", "workflow_grant", "request_workflow", "request_container_detail");
            for (var table : List.of("app_user", "hospital", "campus", "department_campus", "patient", "pathology_case",
                    "user_role_scope", "case_access_scope", "case_assignment", "user_operation_qualification",
                    "workflow_scope", "workflow_grant", "request_workflow", "request_container_detail",
                    "ai_task_grant", "ai_task", "ai_task_outbox", "ai_task_attempt", "ai_task_callback", "ai_task_event", "ai_registry_grant", "ai_model_series", "ai_model_version", "ai_model_state", "ai_model_event", "ai_scan_profile", "ai_assessment", "statistics_grant", "statistics_resource_grant", "statistics_snapshot", "statistics_fact", "storage_grant", "storage_case_grant", "storage_quota", "storage_asset", "storage_version", "storage_finalize", "storage_event", "storage_read_budget", "roi_head", "roi_calibration", "roi_revision", "viewer_manifest", "viewer_read_budget", "digital_qc_grant", "digital_qc_head", "digital_qc_assessment", "digital_qc_event", "scan_grant", "scan_series", "scan_import", "scan_event")) {
                assertThat(f.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
            }
            assertThat(f.jdbc.queryForList("SELECT code FROM security_role WHERE is_template AND enabled ORDER BY code", String.class))
                .containsExactly("CASE_EDITOR_TEMPLATE", "CASE_READER_TEMPLATE", "SECURITY_ADMIN_TEMPLATE");
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM security_role", Integer.class)).isEqualTo(3);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM security_role_permission", Integer.class)).isEqualTo(3);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM security_role_permission WHERE role_code = 'SECURITY_ADMIN_TEMPLATE'", Integer.class)).isZero();
        }
    }

    @Test
    void productionVersionTwoUpgradesAdditivelyPreservingCasesChecksumsAndMissingScopeDenial() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var old = database.configuration("classpath:db/migration").target("2").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(2);
            try (var connection = database.connection()) {
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                String migrationHistory = "SELECT version, checksum FROM flyway_schema_history "
                    + "WHERE success AND version IN ('1', '2') ORDER BY installed_rank";
                var checksums = jdbc.queryForList(migrationHistory);
                assertThat(checksums).hasSize(2);
                assertThat(checksums).extracting(row -> row.get("version")).containsExactly("1", "2");
                assertThat(checksums).allSatisfy(row -> assertThat(row.get("checksum")).isNotNull());
                var hospital = UUID.randomUUID();
                var source = UUID.randomUUID();
                var patient = UUID.randomUUID();
                var request = UUID.randomUUID();
                var pathologyCase = UUID.randomUUID();
                jdbc.update("INSERT INTO hospital (id, code, name) VALUES (?, 'synthetic-upgrade', 'Synthetic hospital')", hospital);
                jdbc.update("INSERT INTO source_system (id, hospital_id, code, name) VALUES (?, ?, 'synthetic-upgrade', 'Synthetic source')", source, hospital);
                jdbc.update("INSERT INTO patient (id, hospital_id) VALUES (?, ?)", patient, hospital);
                jdbc.update("INSERT INTO pathology_request (id, hospital_id, patient_id, source_system_id, request_number) VALUES (?, ?, ?, ?, 'synthetic-upgrade')", request, hospital, patient, source);
                jdbc.update("INSERT INTO pathology_case (id, hospital_id, request_id) VALUES (?, ?, ?)", pathologyCase, hospital, request);
                var latest = database.configuration("classpath:db/migration").target("3").load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(latest.info().current().getVersion().toString()).isEqualTo("3");
                assertThat(latest.validateWithResult().validationSuccessful).isTrue();
                assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(jdbc.queryForList(migrationHistory)).isEqualTo(checksums);
                assertThat(jdbc.queryForObject("SELECT request_id FROM pathology_case WHERE id = ?", UUID.class, pathologyCase)).isEqualTo(request);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM case_access_scope", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
                var user = UUID.randomUUID();
                jdbc.update("INSERT INTO app_user (id, username, display_name, password_hash, enabled, synthetic_only) VALUES (?, 'synthetic-upgrade-user', 'Synthetic user', 'synthetic-test-hash-not-for-login', true, true)", user);
                jdbc.update("INSERT INTO user_role_scope (user_id, role_code, hospital_id, scope_kind, case_filter) VALUES (?, 'CASE_READER_TEMPLATE', ?, 'HOSPITAL', 'ALL_IN_SCOPE')", user, hospital);
                assertThat(new CaseAccessPolicy(jdbc).permits(user, 0, pathologyCase, "CASE_READ")).isFalse();
            }
        }
    }
}
