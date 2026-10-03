package com.pis.integration;
/** Capability boundary only. No signing material or signature creation operations. */
public interface CaAdapter {
 enum Status { NOT_CONFIGURED, UNVERIFIED }
 Status status();
 static CaAdapter unavailable(boolean descriptorPresent) { return () -> descriptorPresent ? Status.UNVERIFIED : Status.NOT_CONFIGURED; }
}
