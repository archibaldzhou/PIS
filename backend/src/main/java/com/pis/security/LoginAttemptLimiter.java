package com.pis.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Bounded, single-process throttle. Deployment-level rate limiting is still required. */
final class LoginAttemptLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAX_KEYS = 4096;
    private final Map<String, Counter> counters = new HashMap<>();
    private final Clock clock;
    LoginAttemptLimiter(Clock clock) { this.clock = clock; }

    synchronized boolean allow(String username, String address) {
        var now = clock.instant();
        counters.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(now));
        String user = "u:" + digest(username);
        String source = "s:" + digest(address);
        int newKeys = (counters.containsKey(user) ? 0 : 1) + (counters.containsKey(source) ? 0 : 1);
        if (counters.size() + newKeys > MAX_KEYS) {
            return false; // Do not evict active limits to make room for attacker-controlled names.
        }
        return increment(user, 20, now) & increment(source, 100, now);
    }
    private boolean increment(String key, int limit, Instant now) {
        var current = counters.getOrDefault(key, new Counter(0, now.plus(WINDOW)));
        int attempts = Math.min(current.attempts() + 1, limit + 1);
        counters.put(key, new Counter(attempts, current.expires()));
        return attempts <= limit;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Counter(int attempts, Instant expires) { }
}
