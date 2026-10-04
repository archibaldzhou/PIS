package com.pis.viewer;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Request-local accounting only. Never caches authorization, QC or dependency decisions. */
public final class ViewerRequestMeter {
    @FunctionalInterface public interface Charge { void persist(OffsetDateTime minute, int requestUnits, int bytes); }
    public record Snapshot(int checks, int requestUnits, long bytes) {}
    private final Map<UUID, OffsetDateTime> paid = new HashMap<>();
    private int checks, requestUnits;
    private long bytes;

    public synchronized Snapshot charge(UUID actor, OffsetDateTime now, int size, Charge charge) {
        if (size < 0 || paid.size() >= 16 && !paid.containsKey(actor)) throw new IllegalArgumentException("Invalid request accounting");
        checks++;
        var minute = paid.get(actor);
        int units = minute == null ? 1 : 0;
        if (units != 0 || size != 0) {
            // Caller commits this accounting transaction before returning. Failure never marks it paid.
            charge.persist(minute == null ? now : minute, units, size);
            paid.putIfAbsent(actor, now);
            requestUnits += units;
            bytes += size;
        }
        return snapshot();
    }
    public synchronized Snapshot snapshot() { return new Snapshot(checks, requestUnits, bytes); }
}
