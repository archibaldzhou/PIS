package com.pis.security.access;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Internal authorization for synthetic case structure, not a clinical API.
 * The caller supplies identity/version from the server-authenticated principal, never request scope.
 * Every decision reads current persisted ownership and authorization in one statement snapshot.
 * Future business writes must enforce this decision atomically with their write/ownership checks;
 * calling this method before an unrelated later write does not solve authorization races.
 */
@Component
public final class CaseAccessPolicy {
    private static final String PERMITS_SQL = """
        SELECT EXISTS (
            SELECT 1
            FROM app_user u
            JOIN case_access_scope c ON c.case_id = :caseId
            JOIN user_role_scope g ON g.user_id = u.id AND g.hospital_id = c.hospital_id
            JOIN security_role r ON r.code = g.role_code AND r.enabled
            JOIN security_role_permission p ON p.role_code = g.role_code AND p.permission_code = :operation
            WHERE u.id = :userId AND u.enabled AND u.auth_version = :authVersion
              AND g.revoked_at IS NULL
              AND g.valid_from <= statement_timestamp()
              AND (g.valid_until IS NULL OR g.valid_until > statement_timestamp())
              AND (g.scope_kind = 'HOSPITAL'
                   OR (g.scope_kind = 'CAMPUS' AND g.campus_id = c.campus_id)
                   OR (g.scope_kind = 'DEPARTMENT' AND g.campus_id = c.campus_id
                       AND g.department_id = c.owning_department_id))
              AND (g.case_filter = 'ALL_IN_SCOPE'
                   OR (g.case_filter = 'ASSIGNED_ONLY' AND EXISTS (
                       SELECT 1 FROM case_assignment a
                       WHERE a.user_id = u.id AND a.hospital_id = c.hospital_id AND a.case_id = c.case_id
                         AND a.scope_version = c.scope_version
                         AND a.revoked_at IS NULL
                         AND a.valid_from <= statement_timestamp()
                         AND (a.valid_until IS NULL OR a.valid_until > statement_timestamp())
                         AND ((:operation = 'CASE_READ' AND a.access_level IN ('READ', 'EDIT'))
                              OR (:operation = 'CASE_EDIT' AND a.access_level = 'EDIT'))
                   )))
              AND (:operation = 'CASE_READ' OR EXISTS (
                  SELECT 1 FROM user_operation_qualification q
                  WHERE q.user_id = u.id AND q.operation_code = :operation AND q.hospital_id = c.hospital_id
                    AND q.revoked_at IS NULL
                    AND q.valid_from <= statement_timestamp()
                    AND (q.valid_until IS NULL OR q.valid_until > statement_timestamp())
                    AND (q.scope_kind = 'HOSPITAL'
                         OR (q.scope_kind = 'CAMPUS' AND q.campus_id = c.campus_id)
                         OR (q.scope_kind = 'DEPARTMENT' AND q.campus_id = c.campus_id
                             AND q.department_id = c.owning_department_id))
              ))
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public CaseAccessPolicy(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public boolean permits(UUID userId, long authVersion, UUID caseId, String operation) {
        if (userId == null || caseId == null || authVersion < 0
                || !("CASE_READ".equals(operation) || "CASE_EDIT".equals(operation))) {
            return false;
        }
        var parameters = new MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("authVersion", authVersion)
            .addValue("caseId", caseId)
            .addValue("operation", operation);
        return Boolean.TRUE.equals(jdbc.queryForObject(PERMITS_SQL, parameters, Boolean.class));
    }

    public void require(UUID userId, long authVersion, UUID caseId, String operation) {
        if (!permits(userId, authVersion, caseId, operation)) {
            throw new AccessDeniedException("Case operation is not permitted");
        }
    }
}
