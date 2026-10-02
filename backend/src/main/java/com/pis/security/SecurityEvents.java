package com.pis.security;

import com.pis.api.TraceIdFilter;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Small T07 integration seam; this is not a durable or compliance-grade audit store. */
@Component
public class SecurityEvents {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityEvents.class);
    private final ApplicationEventPublisher publisher;
    public SecurityEvents(ApplicationEventPublisher publisher) { this.publisher = publisher; }
    public void publish(Type type, UUID actor) { publisher.publishEvent(new Event(type, actor, Instant.now())); }
    @EventListener public void record(Event event) {
        // No submitted usernames, passwords, cookies, CSRF tokens, addresses, or request bodies.
        LOG.info("security_event={} actor={} at={} traceId={}", event.type(), event.actor(), event.at(),
            MDC.get(TraceIdFilter.MDC_KEY));
    }
    public enum Type { LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT, SESSION_REVOKED, LOGIN_THROTTLED }
    public record Event(Type type, UUID actor, Instant at) { }
}
