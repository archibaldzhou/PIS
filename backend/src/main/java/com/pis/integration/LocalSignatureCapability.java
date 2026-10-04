package com.pis.integration;

import com.pis.report.SignatureCapability;
import org.springframework.stereotype.Component;

/** Local capability provider only: no keys, remote endpoints or signing operations. */
@Component
public final class LocalSignatureCapability implements SignatureCapability {
    @Override public Status status() {
        return switch (CaAdapter.unavailable(false).status()) {
            case NOT_CONFIGURED -> Status.NOT_CONFIGURED;
            case UNVERIFIED -> Status.UNVERIFIED;
        };
    }
}
