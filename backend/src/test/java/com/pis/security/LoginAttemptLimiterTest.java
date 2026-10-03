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
    @Test void independentSyntheticSuitesKeepUserBudgetsSeparateWithoutRelaxingLimits() {
        var limiter = new LoginAttemptLimiter(new MutableClock());
        for (int i=0;i<20;i++) assertThat(limiter.allow("auth-suite", "shared-runner")).isTrue();
        assertThat(limiter.allow("auth-suite", "shared-runner")).isFalse();
        for (int i=0;i<20;i++) assertThat(limiter.allow("workflow-suite", "shared-runner")).isTrue();
        assertThat(limiter.allow("workflow-suite", "shared-runner")).isFalse();
        // Distinct users still share the original 100-attempt source ceiling.
        for (int i=0;i<58;i++) assertThat(limiter.allow("other-"+i, "shared-runner")).isTrue();
        assertThat(limiter.allow("one-more", "shared-runner")).isFalse();
    }
    @Test void fileScopedWorkflowAccountsScalePastTwentyWithoutBypassingSourceLimit() {
        var limiter = new LoginAttemptLimiter(new MutableClock());
        // More than twenty files can each authenticate their distinct owner and reviewer.
        for (int file=0;file<30;file++) {
            assertThat(limiter.allow("synthetic.workflow.file"+file,"shared-runner")).isTrue();
            assertThat(limiter.allow("synthetic.technician.file"+file,"shared-runner")).isTrue();
        }
        for (int attempt=0;attempt<19;attempt++) assertThat(limiter.allow("synthetic.workflow.file0","shared-runner")).isTrue();
        assertThat(limiter.allow("synthetic.workflow.file0","shared-runner")).isFalse();
        for (int attempt=0;attempt<20;attempt++) assertThat(limiter.allow("other-file"+attempt,"shared-runner")).isTrue();
        assertThat(limiter.allow("new-file","shared-runner")).isFalse();
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
