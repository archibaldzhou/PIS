package com.pis.security.testfixture;

import com.pis.security.PisPrincipal;
import com.pis.security.AccountRepository;
import com.pis.security.SecurityConfiguration;
import com.pis.security.SecurityEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import com.pis.security.access.CaseAccessPolicy;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** There is deliberately no production case controller or production allowlist for these routes. */
@TestConfiguration(proxyBeanMethods = false)
public class SyntheticAccessTestConfiguration {
    @Bean @Order(0) SecurityFilterChain syntheticProbeChain(HttpSecurity http,
            SecurityConfiguration security, AccountRepository accounts, DaoAuthenticationProvider provider,
            SecurityEvents events, @Value("${server.servlet.session.cookie.name}") String cookieName) throws Exception {
        security.authenticationAndSession(http.securityMatcher("/test/access/**"), accounts, provider, events, cookieName)
            .authorizeHttpRequests(access -> access.anyRequest().authenticated());
        return http.build();
    }
    @Bean SyntheticCaseController syntheticCaseController(CaseAccessPolicy policy) {
        return new SyntheticCaseController(policy);
    }
    @RestController @TestComponent
    static final class SyntheticCaseController {
        private final CaseAccessPolicy policy;
        SyntheticCaseController(CaseAccessPolicy policy) { this.policy = policy; }
        @GetMapping("/test/access/cases/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
        void read(@AuthenticationPrincipal PisPrincipal user, @PathVariable UUID id) {
            policy.require(user.id(), user.authVersion(), id, "CASE_READ");
        }
        @PostMapping("/test/access/cases/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
        void edit(@AuthenticationPrincipal PisPrincipal user, @PathVariable UUID id) {
            policy.require(user.id(), user.authVersion(), id, "CASE_EDIT");
        }
    }
}
