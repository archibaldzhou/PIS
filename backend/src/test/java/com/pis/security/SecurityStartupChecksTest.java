package com.pis.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityStartupChecksTest {
    @Test void rejectsMixedProductionAndDevelopmentProfiles() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> new SecurityStartupChecks(environment, new JdbcTemplate()).run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be combined");
    }
    @Test void rejectsInsecureCookieOutsideDevelopmentAndTests() {
        var environment = new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "false");
        assertThatThrownBy(() -> new SecurityStartupChecks(environment, new JdbcTemplate()).run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure session");
    }
    @Test void rejectsSyntheticUsersAndBootstrapFlagInProduction() {
        var jdbc = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> requiredType) { return requiredType.cast(1L); }
        };
        assertThatThrownBy(() -> new SecurityStartupChecks(new MockEnvironment(), jdbc).run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Synthetic accounts");
        var environment = new MockEnvironment().withProperty("pis.security.development-account.enabled", "true");
        assertThatThrownBy(() -> new SecurityStartupChecks(environment, jdbc).run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Development accounts");
    }
}
