package com.pis.security.access;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessModelConstraintsTest {
    @Test
    void rejectsCrossHospitalCampusDepartmentCaseAndAssignmentLinks() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var a = f.graph();
            var b = f.graph();
            var user = f.user();
            expectSqlState("23503", () -> f.map(a.hospital(), b.campus(), a.department()));
            expectSqlState("23503", () -> f.map(a.hospital(), a.campus(), b.department()));
            expectSqlState("23503", () -> f.scope(a.hospital(), b.pathologyCase(), a.campus(), a.department()));
            var unscoped = f.pathologyCase(a.hospital(), a.request());
            expectSqlState("23503", () -> f.scope(a.hospital(), unscoped, b.campus(), b.department()));
            expectSqlState("23503", () -> f.grant(user, "CASE_READER_TEMPLATE", a.hospital(), "CAMPUS", b.campus(), null, "ALL_IN_SCOPE"));
            expectSqlState("23503", () -> f.qualification(user, a.hospital(), "DEPARTMENT", a.campus(), b.department()));
            expectSqlState("23503", () -> f.execute("""
                INSERT INTO case_assignment (hospital_id, case_id, user_id, access_level, scope_version)
                VALUES (?, ?, ?, 'READ', 0)
                """, a.hospital(), b.pathologyCase(), user));
            expectSqlState("23503", () -> f.execute("""
                INSERT INTO case_assignment (hospital_id, case_id, user_id, access_level, scope_version)
                VALUES (?, ?, ?, 'READ', 0)
                """, a.hospital(), unscoped, user));
            expectSqlState("23503", () -> f.assignment(UUID.randomUUID(), a, "READ", 0));
        }
    }

    @Test
    void rejectsUnmappedSameHospitalDepartmentCampusForGrantsQualificationsAndCases() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var otherCampus = f.campus(graph.hospital());
            var user = f.user();
            expectSqlState("23503", () -> f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), "DEPARTMENT", otherCampus, graph.department(), "ALL_IN_SCOPE"));
            expectSqlState("23503", () -> f.qualification(user, graph.hospital(), "DEPARTMENT", otherCampus, graph.department()));
            expectSqlState("23503", () -> f.execute("UPDATE case_access_scope SET campus_id = ? WHERE case_id = ?", otherCampus, graph.pathologyCase()));
            f.map(graph.hospital(), otherCampus, graph.department());
            f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), "DEPARTMENT", otherCampus, graph.department(), "ALL_IN_SCOPE");
            f.qualification(user, graph.hospital(), "DEPARTMENT", otherCampus, graph.department());
            f.execute("UPDATE case_access_scope SET campus_id = ? WHERE case_id = ?", otherCampus, graph.pathologyCase());
        }
    }

    @Test
    void strictScopeNullShapesCannotCreateAccidentallyBroadGrantsOrQualifications() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            for (var kind : List.of("HOSPITAL", "CAMPUS", "DEPARTMENT")) {
                for (var includeCampus : List.of(false, true)) {
                    for (var includeDepartment : List.of(false, true)) {
                        boolean valid = (kind.equals("HOSPITAL") && !includeCampus && !includeDepartment)
                            || (kind.equals("CAMPUS") && includeCampus && !includeDepartment)
                            || (kind.equals("DEPARTMENT") && includeCampus && includeDepartment);
                        var campus = includeCampus ? graph.campus() : null;
                        var department = includeDepartment ? graph.department() : null;
                        if (valid) {
                            f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), kind, campus, department, "ALL_IN_SCOPE");
                            f.qualification(user, graph.hospital(), kind, campus, department);
                        } else {
                            expectSqlState("23514", () -> f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), kind, campus, department, "ALL_IN_SCOPE"));
                            expectSqlState("23514", () -> f.qualification(user, graph.hospital(), kind, campus, department));
                        }
                    }
                }
            }
            expectSqlState("23514", () -> f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), "ANY", null, null, "ALL_IN_SCOPE"));
            expectSqlState("23514", () -> f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), "HOSPITAL", null, null, "ANY"));
            expectSqlState("23502", () -> f.grant(user, "CASE_READER_TEMPLATE", null, "HOSPITAL", null, null, "ALL_IN_SCOPE"));
        }
    }

    @Test
    void rejectsUnknownClinicalOperationsInvalidAssignmentsAndInvalidValidityWindows() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            var grant = f.grant(user, "CASE_EDITOR_TEMPLATE", graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            var qualification = f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            var assignment = f.assignment(user, graph, "EDIT", 0);
            expectSqlState("23514", () -> f.execute("INSERT INTO security_role_permission VALUES ('SECURITY_ADMIN_TEMPLATE', 'REPORT_SIGN')"));
            expectSqlState("23514", () -> f.execute("UPDATE user_operation_qualification SET operation_code = 'REPORT_SIGN' WHERE id = ?", qualification));
            expectSqlState("23514", () -> f.execute("UPDATE user_operation_qualification SET operation_code = 'CASE_READ' WHERE id = ?", qualification));
            expectSqlState("23514", () -> f.assignment(user, graph, "SIGN", 0));
            expectSqlState("23514", () -> f.assignment(user, graph, "READ", -1));
            var tables = List.of("user_role_scope", "user_operation_qualification", "case_assignment");
            var ids = List.of(grant, qualification, assignment);
            for (int index = 0; index < tables.size(); index++) {
                var table = tables.get(index);
                var id = ids.get(index);
                expectSqlState("23514", () -> f.execute("UPDATE " + table + " SET valid_until = valid_from WHERE id = ?", id));
                expectSqlState("23514", () -> f.execute("UPDATE " + table + " SET valid_until = valid_from - interval '1 second' WHERE id = ?", id));
                expectSqlState("23502", () -> f.execute("UPDATE " + table + " SET valid_from = NULL WHERE id = ?", id));
            }
        }
    }

    @Test
    void normalizedUsernamesAreUniqueAndAccountsAreDisabledByDefaultWithoutForcingSyntheticMarker() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var user = f.insert("""
                INSERT INTO app_user (username, display_name, password_hash)
                VALUES ('synthetic.account', 'Synthetic account', 'synthetic-test-hash-not-for-login')
                """);
            assertThat(f.jdbc.queryForObject("SELECT enabled FROM app_user WHERE id = ?", Boolean.class, user)).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT synthetic_only FROM app_user WHERE id = ?", Boolean.class, user)).isTrue();
            assertThat(f.authVersion(user)).isZero();
            expectSqlState("23505", () -> f.execute("""
                INSERT INTO app_user (username, display_name, password_hash)
                VALUES ('synthetic.account', 'Duplicate', 'synthetic-other-hash')
                """));
            for (var username : List.of("SYNTHETIC.ACCOUNT", " synthetic.account", "synthetic.account ", "", "ab", "bad username", "-bad")) {
                expectSqlState("23514", () -> f.execute("UPDATE app_user SET username = ? WHERE id = ?", username, user));
            }
            expectSqlState("23514", () -> f.execute("UPDATE app_user SET display_name = ' ' WHERE id = ?", user));
            expectSqlState("23514", () -> f.execute("UPDATE app_user SET password_hash = '' WHERE id = ?", user));
            f.execute("UPDATE app_user SET synthetic_only = false WHERE id = ?", user);
            assertThat(f.jdbc.queryForObject("SELECT synthetic_only FROM app_user WHERE id = ?", Boolean.class, user)).isFalse();
        }
    }

    @Test
    void authVersionAutomaticallyIncreasesForCredentialsAndEnablementAndCannotRollBack() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var user = f.user();
            f.execute("UPDATE app_user SET display_name = 'Renamed synthetic user' WHERE id = ?", user);
            assertThat(f.authVersion(user)).isZero();
            f.execute("UPDATE app_user SET password_hash = 'synthetic-replaced-hash', auth_version = 0 WHERE id = ?", user);
            assertThat(f.authVersion(user)).isEqualTo(1);
            f.execute("UPDATE app_user SET enabled = false WHERE id = ?", user);
            assertThat(f.authVersion(user)).isEqualTo(2);
            f.execute("UPDATE app_user SET enabled = false, password_hash = password_hash WHERE id = ?", user);
            assertThat(f.authVersion(user)).isEqualTo(2);
            f.execute("UPDATE app_user SET auth_version = 10 WHERE id = ?", user);
            assertThat(f.authVersion(user)).isEqualTo(10);
            expectSqlState("23514", () -> f.execute("UPDATE app_user SET auth_version = 9 WHERE id = ?", user));
            expectSqlState("23514", () -> f.execute("UPDATE app_user SET auth_version = NULL WHERE id = ?", user));
            expectSqlState("23514", () -> f.execute("UPDATE app_user SET password_hash = 'synthetic-attempt-hash', auth_version = 0 WHERE id = ?", user));
            assertThat(f.authVersion(user)).isEqualTo(10);
        }
    }

    @Test
    void caseScopeVersionTracksOwnershipCannotRollBackAndCannotRetargetScopeIdentity() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var newDepartment = f.department(graph.hospital(), graph.campus());
            f.execute("UPDATE case_access_scope SET owning_department_id = ? WHERE case_id = ?", newDepartment, graph.pathologyCase());
            assertThat(f.jdbc.queryForObject("SELECT scope_version FROM case_access_scope", Long.class)).isEqualTo(1);
            f.execute("UPDATE case_access_scope SET owning_department_id = owning_department_id WHERE case_id = ?", graph.pathologyCase());
            assertThat(f.jdbc.queryForObject("SELECT scope_version FROM case_access_scope", Long.class)).isEqualTo(1);
            f.execute("UPDATE case_access_scope SET scope_version = 7 WHERE case_id = ?", graph.pathologyCase());
            expectSqlState("23514", () -> f.execute("UPDATE case_access_scope SET scope_version = 6 WHERE case_id = ?", graph.pathologyCase()));
            expectSqlState("23514", () -> f.execute("UPDATE case_access_scope SET scope_version = NULL WHERE case_id = ?", graph.pathologyCase()));
            expectSqlState("23514", () -> f.execute("UPDATE case_access_scope SET campus_id = ?, scope_version = 0 WHERE case_id = ?", graph.campus(), graph.pathologyCase()));
            expectSqlState("23514", () -> f.execute("UPDATE case_access_scope SET case_id = ? WHERE case_id = ?", UUID.randomUUID(), graph.pathologyCase()));
            expectSqlState("23514", () -> f.execute("UPDATE case_access_scope SET hospital_id = ? WHERE case_id = ?", UUID.randomUUID(), graph.pathologyCase()));
            assertThat(f.jdbc.queryForObject("SELECT scope_version FROM case_access_scope", Long.class)).isEqualTo(7);
        }
    }

    @Test
    void referencedSecurityAndOwnershipRowsCannotBeDeletedByCascade() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, "CASE_READER_TEMPLATE", graph.hospital(), "DEPARTMENT", graph.campus(), graph.department(), "ASSIGNED_ONLY");
            f.assignment(user, graph, "READ", 0);
            for (var table : List.of("app_user", "security_role", "campus", "department_campus", "case_access_scope", "pathology_case")) {
                expectSqlState("23503", () -> f.execute("DELETE FROM " + table));
            }
        }
    }

    private static void expectSqlState(String state, Executable action) {
        assertThat(assertThrows(SQLException.class, action).getSQLState()).isEqualTo(state);
    }
}
