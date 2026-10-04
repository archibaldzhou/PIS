package com.pis.report;

/** Report-owned outbound capability port. Neither status authorizes clinical signing. */
public interface SignatureCapability {
    enum Status { NOT_CONFIGURED, UNVERIFIED }
    Status status();
}
