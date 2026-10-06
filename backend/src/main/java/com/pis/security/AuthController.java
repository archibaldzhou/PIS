package com.pis.security;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public AuthController(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @GetMapping("/api/auth/csrf") public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }
    @GetMapping("/api/auth/me") public CurrentUser me(@AuthenticationPrincipal PisPrincipal principal) {
        boolean change=Boolean.TRUE.equals(jdbc.queryForObject("SELECT password_change_required FROM app_user WHERE id=?",Boolean.class,principal.id()));
        boolean admin=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity_admin_grant WHERE user_id=? AND revoked_at IS NULL AND valid_until>statement_timestamp()) OR EXISTS(SELECT 1 FROM identity_scope_assignment a JOIN workflow_grant g ON g.user_id=a.user_id AND g.scope_id=a.scope_id JOIN workflow_scope s ON s.id=a.scope_id WHERE a.user_id=? AND 'AUDIT'=ANY(a.permissions) AND a.valid_until>statement_timestamp() AND s.enabled AND g.can_read AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp()))",Boolean.class,principal.id(),principal.id()));
        return new CurrentUser(principal.id(), principal.getUsername(), principal.displayName(),change,admin);
    }
    public record CsrfResponse(String headerName, String token) { }
    public record CurrentUser(UUID id, String username, String displayName,boolean passwordChangeRequired,boolean administration) { }
}
