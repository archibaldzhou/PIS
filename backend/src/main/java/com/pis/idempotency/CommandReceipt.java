package com.pis.idempotency;

import com.pis.audit.AuditRecorder;
import java.util.UUID;

/** Bounded non-clinical result, suitable for replay without storing patient payload snapshots. */
public record CommandReceipt(int status, String resourceType, UUID resourceId, long version) {
    public CommandReceipt {
        AuditRecorder.requireToken(resourceType, 64);
        if ((status != 200 && status != 201) || resourceId == null || version < 0) {
            throw new IllegalArgumentException("Invalid command receipt");
        }
    }
}
