package com.pis.security.access;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaseAccessPolicyTest {
    private static final String READER = "CASE_READER_TEMPLATE";
    private static final String EDITOR = "CASE_EDITOR_TEMPLATE";

    @Test
    void defaultsToDenyForAbsentIdentityGrantOrAuthoritativeScope() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            f.grant(user, READER, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            var unscoped = f.pathologyCase(graph.hospital(), graph.request());
            assertThat(f.policy.permits(user, 0, unscoped, "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, UUID.randomUUID(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(UUID.randomUUID(), 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(null, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, null, "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, -1, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThrows(AccessDeniedException.class, () -> f.policy.require(user, 0, unscoped, "CASE_READ"));
            assertDoesNotThrow(() -> f.policy.require(user, 0, graph.pathologyCase(), "CASE_READ"));
        }
    }

    @Test
    void hospitalScopeCoversOnlyPersistedHospitalEvenWithAssignmentElsewhere() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var a = f.graph();
            var b = f.graph();
            var user = f.user();
            f.grant(user, READER, a.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.assignment(user, b, "EDIT", 0);
            var otherCampus = f.campus(a.hospital());
            var otherDepartment = f.department(a.hospital(), otherCampus);
            var sameHospital = f.anotherCase(a, otherCampus, otherDepartment);
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, sameHospital, "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, b.pathologyCase(), "CASE_READ")).isFalse();
        }
    }

    @Test
    void campusScopeDoesNotCrossCampusAndDepartmentScopeRequiresBothDimensions() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var otherCampus = f.campus(graph.hospital());
            f.map(graph.hospital(), otherCampus, graph.department());
            var sameDepartmentOtherCampus = f.anotherCase(graph, otherCampus, graph.department());
            var otherDepartment = f.department(graph.hospital(), graph.campus());
            var sameCampusOtherDepartment = f.anotherCase(graph, graph.campus(), otherDepartment);
            var user = f.user();
            var grant = f.grant(user, READER, graph.hospital(), "CAMPUS", graph.campus(), null, "ALL_IN_SCOPE");
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, sameCampusOtherDepartment, "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, sameDepartmentOtherCampus, "CASE_READ")).isFalse();
            f.execute("UPDATE user_role_scope SET scope_kind = 'DEPARTMENT', department_id = ? WHERE id = ?", graph.department(), grant);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, sameCampusOtherDepartment, "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, sameDepartmentOtherCampus, "CASE_READ")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked_at = statement_timestamp()", "valid_from = statement_timestamp() + interval '1 day'",
        "valid_from = statement_timestamp() - interval '2 days', valid_until = statement_timestamp() - interval '1 day'"})
    void revokedFutureAndExpiredGrantsDenyOnNextDecision(String change) throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            var grant = f.grant(user, READER, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            f.execute("UPDATE user_role_scope SET " + change + " WHERE id = ?", grant);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
        }
    }

    @Test
    void disabledRolesRemovedPermissionsAndDisabledUsersTakeEffectWithoutCachedAuthorities() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, READER, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.execute("UPDATE security_role SET enabled = false WHERE code = ?", READER);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            f.execute("UPDATE security_role SET enabled = true WHERE code = ?", READER);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            f.execute("DELETE FROM security_role_permission WHERE role_code = ?", READER);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            f.execute("INSERT INTO security_role_permission VALUES (?, 'CASE_READ')", READER);
            f.execute("UPDATE app_user SET enabled = false WHERE id = ?", user);
            assertThat(f.policy.permits(user, f.authVersion(user), graph.pathologyCase(), "CASE_READ")).isFalse();
            f.execute("UPDATE app_user SET enabled = true WHERE id = ?", user);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, f.authVersion(user), graph.pathologyCase(), "CASE_READ")).isTrue();
            var current = f.authVersion(user);
            f.execute("UPDATE app_user SET password_hash = 'synthetic-replacement-hash' WHERE id = ?", user);
            assertThat(f.policy.permits(user, current, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, f.authVersion(user), graph.pathologyCase(), "CASE_READ")).isTrue();
        }
    }

    @Test
    void readerCannotEditAndEditorStillNeedsMatchingQualification() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            var grant = f.grant(user, READER, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            var qualification = f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            f.execute("UPDATE user_role_scope SET role_code = ? WHERE id = ?", EDITOR, grant);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isTrue();
            f.execute("DELETE FROM user_operation_qualification WHERE id = ?", qualification);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
        }
    }

    @Test
    void qualificationMustMatchTheActualHospitalCampusAndDepartment() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var a = f.graph();
            var b = f.graph();
            var user = f.user();
            f.grant(user, EDITOR, a.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.qualification(user, b.hospital(), "HOSPITAL", null, null);
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isFalse();
            var otherCampus = f.campus(a.hospital());
            f.map(a.hospital(), otherCampus, a.department());
            f.qualification(user, a.hospital(), "CAMPUS", otherCampus, null);
            f.qualification(user, a.hospital(), "DEPARTMENT", otherCampus, a.department());
            var otherDepartment = f.department(a.hospital(), a.campus());
            f.qualification(user, a.hospital(), "DEPARTMENT", a.campus(), otherDepartment);
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isFalse();
            f.qualification(user, a.hospital(), "DEPARTMENT", a.campus(), a.department());
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked_at = statement_timestamp()", "valid_from = statement_timestamp() + interval '1 day'",
        "valid_from = statement_timestamp() - interval '2 days', valid_until = statement_timestamp() - interval '1 day'"})
    void revokedFutureAndExpiredQualificationsBlockEditButNotRead(String change) throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, EDITOR, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            var qualification = f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isTrue();
            f.execute("UPDATE user_operation_qualification SET " + change + " WHERE id = ?", qualification);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
        }
    }

    @Test
    void assignmentRequiresMatchingUserCaseAndOperationAndNeverCreatesARole() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.assignment(user, graph, "EDIT", 0);
            f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            f.execute("DELETE FROM case_assignment");
            f.grant(user, EDITOR, graph.hospital(), "HOSPITAL", null, null, "ASSIGNED_ONLY");
            f.assignment(f.user(), graph, "EDIT", 0);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            var assignment = f.assignment(user, graph, "READ", 0);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            var otherCase = f.anotherCase(graph, graph.campus(), graph.department());
            assertThat(f.policy.permits(user, 0, otherCase, "CASE_READ")).isFalse();
            f.execute("UPDATE case_assignment SET access_level = 'EDIT' WHERE id = ?", assignment);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked_at = statement_timestamp()", "valid_from = statement_timestamp() + interval '1 day'",
        "valid_from = statement_timestamp() - interval '2 days', valid_until = statement_timestamp() - interval '1 day'"})
    void revokedFutureAndExpiredAssignmentsAreNotUsable(String change) throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, READER, graph.hospital(), "HOSPITAL", null, null, "ASSIGNED_ONLY");
            var assignment = f.assignment(user, graph, "READ", 0);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            f.execute("UPDATE case_assignment SET " + change + " WHERE id = ?", assignment);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
        }
    }

    @Test
    void cannotSplicePermissionScopeOrAllInScopeFilterFromDifferentGrants() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var a = f.graph();
            var b = f.graph();
            var user = f.user();
            // Read is broad in A, edit is broad in B. No edit permission in A can be borrowed from B.
            f.grant(user, READER, a.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.grant(user, EDITOR, b.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.qualification(user, a.hospital(), "HOSPITAL", null, null);
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isFalse();
            // Editor in A needs assignment; the reader's ALL_IN_SCOPE must not satisfy its filter.
            f.grant(user, EDITOR, a.hospital(), "HOSPITAL", null, null, "ASSIGNED_ONLY");
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isFalse();
            f.assignment(user, a, "READ", 0);
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isFalse();
            f.assignment(user, a, "EDIT", 0);
            assertThat(f.policy.permits(user, 0, a.pathologyCase(), "CASE_EDIT")).isTrue();
        }
    }

    @Test
    void cannotBorrowEditPermissionFromAnotherDepartmentEvenWithAValidAssignment() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var otherDepartment = f.department(graph.hospital(), graph.campus());
            var user = f.user();
            f.grant(user, READER, graph.hospital(), "DEPARTMENT", graph.campus(), graph.department(), "ALL_IN_SCOPE");
            f.grant(user, EDITOR, graph.hospital(), "DEPARTMENT", graph.campus(), otherDepartment, "ASSIGNED_ONLY");
            f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            f.assignment(user, graph, "EDIT", 0);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isTrue();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
        }
    }

    @Test
    void editPermissionDoesNotImplicitlyCreateReadPermission() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, EDITOR, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            f.execute("DELETE FROM security_role_permission WHERE role_code = ? AND permission_code = 'CASE_READ'", EDITOR);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isTrue();
        }
    }

    @Test
    void actualOwnershipTransferInvalidatesOldAssignmentsAndOldDepartmentGrant() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, EDITOR, graph.hospital(), "HOSPITAL", null, null, "ASSIGNED_ONLY");
            f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            f.assignment(user, graph, "EDIT", 0);
            var departmentUser = f.user();
            f.grant(departmentUser, READER, graph.hospital(), "DEPARTMENT", graph.campus(), graph.department(), "ALL_IN_SCOPE");
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isTrue();
            var newCampus = f.campus(graph.hospital());
            var newDepartment = f.department(graph.hospital(), newCampus);
            // The original requesting department remains unchanged and must not restore access.
            f.execute("UPDATE case_access_scope SET campus_id = ?, owning_department_id = ? WHERE case_id = ?", newCampus, newDepartment, graph.pathologyCase());
            assertThat(f.jdbc.queryForObject("SELECT scope_version FROM case_access_scope WHERE case_id = ?", Long.class, graph.pathologyCase())).isEqualTo(1);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            assertThat(f.policy.permits(departmentUser, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            f.assignment(user, graph, "EDIT", 1);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isTrue();
            f.execute("UPDATE case_access_scope SET campus_id = ?, owning_department_id = ? WHERE case_id = ?", graph.campus(), graph.department(), graph.pathologyCase());
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT scope_version FROM case_access_scope WHERE case_id = ?", Long.class, graph.pathologyCase())).isEqualTo(2);
        }
    }

    @Test
    void administrativeTemplateAndUnsupportedClinicalSigningRemainDenied() throws Exception {
        try (var f = new AccessPolicyTestFixture()) {
            var graph = f.graph();
            var user = f.user();
            f.grant(user, "SECURITY_ADMIN_TEMPLATE", graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            f.assignment(user, graph, "EDIT", 0);
            f.qualification(user, graph.hospital(), "HOSPITAL", null, null);
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_READ")).isFalse();
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), "CASE_EDIT")).isFalse();
            f.grant(user, EDITOR, graph.hospital(), "HOSPITAL", null, null, "ALL_IN_SCOPE");
            for (var operation : List.of("REPORT_SIGN", "CASE_DELETE", "case_read", "", " CASE_READ", "CASE_READ' OR true--")) {
                assertThat(f.policy.permits(user, 0, graph.pathologyCase(), operation)).isFalse();
                assertThrows(AccessDeniedException.class, () -> f.policy.require(user, 0, graph.pathologyCase(), operation));
            }
            assertThat(f.policy.permits(user, 0, graph.pathologyCase(), null)).isFalse();
        }
    }

    @Test
    void publicPolicySignatureCannotAcceptClientSuppliedHospitalCampusOrDepartment() {
        var methods = Arrays.stream(CaseAccessPolicy.class.getDeclaredMethods())
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList();
        assertThat(methods).hasSize(2);
        for (var method : methods) {
            assertThat(method.getName()).isIn("permits", "require");
            assertThat(method.getParameterTypes()).containsExactly(UUID.class, long.class, UUID.class, String.class);
        }
    }
}
