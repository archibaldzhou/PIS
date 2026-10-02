package com.pis.security;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    @GetMapping("/api/auth/csrf") public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }
    @GetMapping("/api/auth/me") public CurrentUser me(@AuthenticationPrincipal PisPrincipal principal) {
        return new CurrentUser(principal.id(), principal.getUsername(), principal.displayName());
    }
    public record CsrfResponse(String headerName, String token) { }
    public record CurrentUser(UUID id, String username, String displayName) { }
}
