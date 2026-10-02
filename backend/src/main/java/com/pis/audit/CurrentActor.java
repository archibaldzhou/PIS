package com.pis.audit;

import com.pis.security.PisPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Resolves identity from server authentication, never from a command or HTTP field. */
@Component
public final class CurrentActor {
    private final JdbcTemplate jdbc;

    public CurrentActor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Actor require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof PisPrincipal principal) || !principal.isEnabled()) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated application identity is required");
        }
        boolean current = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND enabled AND auth_version = ?)
            """, Boolean.class, principal.id(), principal.authVersion()));
        if (!current) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated application identity is no longer current");
        }
        return new Actor(principal.id(), principal.authVersion());
    }

    public record Actor(UUID id, long authVersion) {
        public Actor {
            if (id == null || authVersion < 0) throw new IllegalArgumentException("Invalid application identity");
        }
    }
}
