package com.pis.security;

import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class SecurityStartupChecks implements ApplicationRunner {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    SecurityStartupChecks(Environment environment, JdbcTemplate jdbc) { this.environment = environment; this.jdbc = jdbc; }
    @Override public void run(ApplicationArguments arguments) {
        var profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        boolean development = profiles.contains("dev");
        boolean test = profiles.contains("test");
        boolean production = profiles.contains("prod");
        if (production && (development || test)) {
            throw new IllegalStateException("prod cannot be combined with dev or test");
        }
        if (!development && !test) {
            if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) {
                throw new IllegalStateException("Secure session cookies are required outside dev/test");
            }
            if (environment.getProperty("pis.security.development-account.enabled", Boolean.class, false)) {
                throw new IllegalStateException("Development accounts cannot be enabled outside dev");
            }
            Long count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE synthetic_only", Long.class);
            if (count != null && count != 0) {
                throw new IllegalStateException("Synthetic accounts cannot be used outside dev/test");
            }
        }
    }
}
