package com.pis.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginAttemptFilter extends OncePerRequestFilter {
    private final LoginAttemptLimiter limiter;
    private final SecurityEvents events;
    LoginAttemptFilter(LoginAttemptLimiter limiter, SecurityEvents events) { this.limiter = limiter; this.events = events; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/auth/login".equals(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        if (username == null || username.length() > 64 || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            events.publish(SecurityEvents.Type.LOGIN_FAILURE, null);
            SecurityResponses.authenticationFailed(response);
            return;
        }
        if (!limiter.allow(username.trim().toLowerCase(Locale.ROOT), request.getRemoteAddr())) {
            events.publish(SecurityEvents.Type.LOGIN_THROTTLED, null);
            response.setHeader("Retry-After", "300");
            SecurityResponses.error(response, 429, "LOGIN_THROTTLED", "登录尝试过于频繁，请稍后再试");
            return;
        }
        chain.doFilter(request, response);
    }
}
