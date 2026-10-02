package com.pis.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {
    @Test void limitsUserAcrossSourcesAndAllowsRetryAfterWindow() {
        var clock = new MutableClock();
        var limiter = new LoginAttemptLimiter(clock);
        for (int i = 0; i < 20; i++) { assertThat(limiter.allow("synthetic", "source-" + i)).isTrue(); }
        assertThat(limiter.allow("synthetic", "new-source")).isFalse();
        clock.now = clock.now.plusSeconds(301);
        assertThat(limiter.allow("synthetic", "new-source")).isTrue();
    }
    @Test void limitsSourceEvenAcrossUnknownUsernames() {
        var limiter = new LoginAttemptLimiter(new MutableClock());
        for (int i = 0; i < 100; i++) { assertThat(limiter.allow("unknown-" + i, "one-source")).isTrue(); }
        assertThat(limiter.allow("another-unknown", "one-source")).isFalse();
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
