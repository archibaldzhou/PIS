package com.pis.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityStartupChecksTest {
    @Test void operationalOverridesFailClosedWithoutEchoingSubmittedValues() {
        var unsafe=java.util.Map.of("management.endpoints.web.exposure.include","health,env",
            "management.endpoints.jmx.exposure.exclude","", "management.endpoint.health.show-details","always",
            "management.endpoint.health.show-components","always", "spring.flyway.clean-disabled","false",
            "spring.flyway.validate-on-migrate","false", "spring.flyway.out-of-order","true",
            "spring.flyway.baseline-on-migrate","true");
        for(var entry:unsafe.entrySet()) {
            assertThatThrownBy(()->SecurityStartupChecks.validateOperationalSettings(new MockEnvironment().withProperty(entry.getKey(),entry.getValue())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unsafe operations configuration");
        }
        SecurityStartupChecks.validateOperationalSettings(new MockEnvironment());
    }
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
