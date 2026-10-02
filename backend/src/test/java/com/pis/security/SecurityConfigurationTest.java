package com.pis.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {
    @Test void storesOnlyAdaptiveHashesAndRejectsLegacyPlainOrUnknownFormats() {
        var encoder = new SecurityConfiguration().passwordEncoder();
        String raw = "Synthetic-password-42!";
        String hash = encoder.encode(raw);
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(raw);
        assertThat(encoder.matches(raw, hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
        assertThat(encoder.matches(raw, "{noop}" + raw)).isFalse();
        assertThat(encoder.matches(raw, "{unsupported}value")).isFalse();
        assertThat(encoder.matches(raw, raw)).isFalse();
    }
    @Test void principalErasesPasswordAndUsesStableIdentityWithoutCachedRoleAuthorities() {
        var id = UUID.randomUUID();
        var principal = new PisPrincipal(new AccountRepository.Account(id, "synthetic.one", "Synthetic", "hash", true, 0));
        var changed = new PisPrincipal(new AccountRepository.Account(id, "synthetic.one", "Renamed", "different", false, 9));
        assertThat(principal).isEqualTo(changed);
        assertThat(principal.hashCode()).isEqualTo(changed.hashCode());
        assertThat(principal.getAuthorities()).isEmpty();
        principal.eraseCredentials();
        assertThat(principal.getPassword()).isNull();
    }
}
