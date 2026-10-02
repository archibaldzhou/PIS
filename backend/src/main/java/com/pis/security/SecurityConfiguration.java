package com.pis.security;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() {
        var encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
        encoder.setDefaultPasswordEncoderForMatches(new PasswordEncoder() {
            @Override public String encode(CharSequence rawPassword) { throw new UnsupportedOperationException("Unsupported password encoding"); }
            @Override public boolean matches(CharSequence rawPassword, String encodedPassword) { return false; }
        });
        return encoder;
    }
    @Bean UserDetailsService userDetailsService(AccountRepository accounts) {
        return username -> accounts.findByUsername(username.trim().toLowerCase(Locale.ROOT))
            .map(PisPrincipal::new).orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
    }
    @Bean DaoAuthenticationProvider authenticationProvider(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setHideUserNotFoundExceptions(true);
        // Check status after the password hash, so disabled accounts do not take the fast path.
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) { throw new DisabledException("Account unavailable"); }
        });
        return provider;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, AccountRepository accounts,
            DaoAuthenticationProvider provider, SecurityEvents events,
            @Value("${server.servlet.session.cookie.name}") String cookieName) throws Exception {
        authenticationAndSession(http, accounts, provider, events, cookieName)
            .authorizeHttpRequests(access -> access
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health",
                    "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/hello").authenticated()
                .requestMatchers("/api/requests", "/api/requests/**", "/api/receptions/**", "/api/labels/**", "/api/grossing/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    /** Shared security mechanics. Test-only resource probes change only the route allowlist. */
    public HttpSecurity authenticationAndSession(HttpSecurity http, AccountRepository accounts,
            DaoAuthenticationProvider provider, SecurityEvents events, String cookieName) throws Exception {
        AccessDeniedHandler denied = (request, response, error) -> {
            if (error instanceof CsrfException) {
                SecurityResponses.error(response, 403, "CSRF_INVALID", "安全令牌已失效，请刷新后重试");
            } else {
                SecurityResponses.error(response, 403, "ACCESS_DENIED", "没有执行此操作的权限");
            }
        };
        http.authenticationProvider(provider)
            .httpBasic(basic -> basic.disable())
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId()))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                    SecurityResponses.error(response, 401, "UNAUTHENTICATED", "请先登录"))
                .accessDeniedHandler(denied))
            .formLogin(login -> login.loginPage("/").loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> {
                    var principal = (PisPrincipal) authentication.getPrincipal();
                    events.publish(SecurityEvents.Type.LOGIN_SUCCESS, principal.id());
                    response.setHeader("Cache-Control", "no-store");
                    response.setStatus(204);
                })
                .failureHandler((request, response, error) -> {
                    events.publish(SecurityEvents.Type.LOGIN_FAILURE, null);
                    SecurityResponses.authenticationFailed(response);
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                .clearAuthentication(true).deleteCookies(cookieName)
                .logoutSuccessHandler((request, response, authentication) -> {
                    var actor = authentication != null && authentication.getPrincipal() instanceof PisPrincipal principal
                        ? principal.id() : null;
                    events.publish(SecurityEvents.Type.LOGOUT, actor);
                    response.setHeader("Cache-Control", "no-store");
                    response.setStatus(204);
                }))
            .addFilterAfter(new AccountSessionValidationFilter(accounts, events, cookieName), SecurityContextHolderFilter.class)
            .addFilterBefore(new LoginAttemptFilter(new LoginAttemptLimiter(Clock.systemUTC()), events),
                UsernamePasswordAuthenticationFilter.class);
        return http;
    }
}
