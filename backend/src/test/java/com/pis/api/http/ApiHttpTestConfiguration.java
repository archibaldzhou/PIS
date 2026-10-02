package com.pis.api.http;

import com.pis.api.ApiException;
import com.pis.api.TraceIdFilter;
import com.pis.audit.CurrentActor;
import com.pis.idempotency.CommandReceipt;
import com.pis.idempotency.IdempotentCommands;
import com.pis.security.AccountRepository;
import com.pis.security.SecurityConfiguration;
import com.pis.security.SecurityEvents;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Only test routes and non-clinical probe resources; never included in the production artifact. */
@TestConfiguration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ApiHttpTestConfiguration {
    static final String ROOT = "/test/api-contract";
    static final String SECRET = "synthetic-private-http-marker";
    static final String BEFORE_ERROR_TRACE = "X-Probe-Before-Error-Trace";
    static final String OPERATION = "HTTP_PROBE_INCREMENT_V1";
    static final String RESOURCE_TYPE = "HTTP_PROBE";

    @Bean
    @Order(0)
    SecurityFilterChain apiHttpProbeChain(HttpSecurity http, SecurityConfiguration security,
            AccountRepository accounts, DaoAuthenticationProvider provider, SecurityEvents events,
            @Value("${server.servlet.session.cookie.name}") String cookieName) throws Exception {
        security.authenticationAndSession(http.securityMatcher(ROOT + "/**"),
                accounts, provider, events, cookieName)
            .authorizeHttpRequests(access -> access.anyRequest().authenticated());
        return http.build();
    }

    public enum Mode { SYNTHETIC }
    public record BodyRequest(@NotBlank String label, @NotNull @Min(1) Integer count,
                              @NotNull Mode mode) { }
    public record MutationRequest(@NotNull @Min(0) Long expectedVersion,
                                  @NotNull @Min(1) @Max(10) Integer increment) { }
    // Path/resource identity is semantic input too; it must be included in the request fingerprint.
    public record ProbeCommand(UUID resourceId, long expectedVersion, int increment) { }

    // Nested configuration components are registered once by Spring's configuration parser.
    // Do not add an additional @Bean factory; @TestComponent excludes production component scans.
    @RestController
    @TestComponent
    public static class HttpContractController {
        private final JdbcTemplate jdbc;
        private final IdempotentCommands commands;

        HttpContractController(JdbcTemplate jdbc, IdempotentCommands commands) {
            this.jdbc = jdbc;
            this.commands = commands;
        }

        @PostMapping(ROOT + "/body")
        public Map<String, Boolean> body(@Valid @RequestBody BodyRequest body) {
            return Map.of("ok", true);
        }

        @GetMapping(ROOT + "/query")
        public Map<String, Integer> query(@RequestParam("count") @Min(1) int count,
                                          @RequestParam("mode") Mode mode) {
            return Map.of("count", count);
        }

        @GetMapping(ROOT + "/guarded")
        @PreAuthorize("denyAll()")
        public Map<String, Boolean> guarded() { return Map.of("unreachable", true); }

        @GetMapping(ROOT + "/wrapped-denied")
        public void wrappedDenied() { throw new IllegalStateException(SECRET, new AccessDeniedException(SECRET)); }

        @GetMapping(ROOT + "/invalid-return")
        @NotBlank
        public String invalidReturn() { return ""; }

        @GetMapping(ROOT + "/throw")
        public void unexpected() { throw new IllegalStateException("SQL password " + SECRET); }

        @GetMapping(ROOT + "/send-error/{status}")
        public void sendError(@PathVariable int status, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
            response.setHeader(BEFORE_ERROR_TRACE, TraceIdFilter.traceId(request).toString());
            response.sendError(status, "SQL password " + SECRET);
        }

        @PostMapping(ROOT + "/probes/{id}")
        public ResponseEntity<CommandReceipt> mutate(@PathVariable UUID id,
                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                @Valid @RequestBody MutationRequest body) {
            // The hospital comes from the server's current resource, never from a header or DTO.
            var hospitals = jdbc.query("SELECT hospital_id FROM http_probe_resource WHERE id = ?",
                (row, index) -> row.getObject(1, UUID.class), id);
            if (hospitals.isEmpty()) throw new AccessDeniedException(SECRET);
            UUID hospital = hospitals.getFirst();
            var command = new ProbeCommand(id, body.expectedVersion(), body.increment());
            var result = commands.execute(hospital, OPERATION, key, command, new IdempotentCommands.Work() {
                @Override
                public void authorize(CurrentActor.Actor actor) { requirePermission(actor, hospital, id); }

                @Override
                public IdempotentCommands.Mutation mutate(CurrentActor.Actor actor) {
                    // One statement enforces the current actor, permission, hospital ownership and version.
                    var versions = jdbc.query("""
                        UPDATE http_probe_resource target
                        SET counter = target.counter + ?, version = target.version + 1
                        WHERE target.id = ? AND target.hospital_id = ? AND target.version = ?
                          AND EXISTS (
                            SELECT 1 FROM http_probe_permission p JOIN app_user u ON u.id = p.actor_id
                            WHERE p.actor_id = ? AND p.resource_id = target.id
                              AND p.hospital_id = target.hospital_id AND p.enabled
                              AND u.enabled AND u.auth_version = ?)
                        RETURNING version
                        """, (row, index) -> row.getLong(1), command.increment(), id, hospital,
                        command.expectedVersion(), actor.id(), actor.authVersion());
                    if (versions.isEmpty()) {
                        authorize(actor);
                        throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                            "The resource version has changed.");
                    }
                    return new IdempotentCommands.Mutation(
                        new CommandReceipt(200, RESOURCE_TYPE, id, versions.getFirst()), command.expectedVersion());
                }

                @Override
                public void authorizeReplay(CurrentActor.Actor actor, CommandReceipt receipt) {
                    if (!RESOURCE_TYPE.equals(receipt.resourceType())) throw new AccessDeniedException(SECRET);
                    requirePermission(actor, hospital, receipt.resourceId());
                }
            });
            return ResponseEntity.status(result.receipt().status())
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.receipt());
        }

        private void requirePermission(CurrentActor.Actor actor, UUID hospital, UUID resource) {
            boolean permitted = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM http_probe_permission p
                    JOIN http_probe_resource r ON r.id = p.resource_id AND r.hospital_id = p.hospital_id
                    JOIN app_user u ON u.id = p.actor_id
                    WHERE p.actor_id = ? AND p.hospital_id = ? AND p.resource_id = ?
                      AND p.enabled AND u.enabled AND u.auth_version = ?)
                """, Boolean.class, actor.id(), hospital, resource, actor.authVersion()));
            if (!permitted) throw new AccessDeniedException(SECRET);
        }
    }
}
