package com.pis.integration;

import com.pis.report.SignatureCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LocalSignatureCapabilityTest {
    @Test void localProviderCannotAdvertiseClinicalSigningOrConfiguredCa() {
        SignatureCapability capability=new LocalSignatureCapability();
        assertThat(capability.status()).isEqualTo(SignatureCapability.Status.NOT_CONFIGURED);
        assertThat(SignatureCapability.Status.values()).containsExactly(
            SignatureCapability.Status.NOT_CONFIGURED,SignatureCapability.Status.UNVERIFIED);
        assertThat(CaAdapter.unavailable(true).status()).isEqualTo(CaAdapter.Status.UNVERIFIED);
    }
}
