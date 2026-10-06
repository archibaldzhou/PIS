package com.pis.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class AccountSessionValidationFilter extends OncePerRequestFilter {
    private final AccountRepository accounts;
    private final SecurityEvents events;
    private final CookieClearingLogoutHandler clearCookie;
    AccountSessionValidationFilter(AccountRepository accounts, SecurityEvents events, String cookieName) {
        this.accounts = accounts; this.events = events;
        this.clearCookie = new CookieClearingLogoutHandler(cookieName);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PisPrincipal principal) {
            try {
                var state = accounts.state(principal.id());
                if (state.isEmpty() || !state.get().enabled() || state.get().authVersion() != principal.authVersion()) {
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    clearCookie.logout(request, response, authentication);
                    events.publish(SecurityEvents.Type.SESSION_REVOKED, principal.id());
                    SecurityResponses.error(response, 401, "SESSION_EXPIRED", "会话已失效，请重新登录");
                    return;
                }
                if (state.get().passwordChangeRequired() && !java.util.Set.of("/api/auth/me", "/api/auth/csrf", "/api/auth/logout", "/api/auth/password", "/api/hello").contains(request.getRequestURI())) {
                    SecurityResponses.error(response, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始或重置密码");
                    return;
                }
            } catch (DataAccessException unavailable) {
                // Never authorize using stale identity when its current status cannot be checked.
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                    clearCookie.logout(request, response, authentication);
                SecurityResponses.error(response, 503, "AUTHENTICATION_UNAVAILABLE", "暂时无法验证会话，请稍后重试");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
