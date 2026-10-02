package com.pis.security.testfixture;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration(proxyBeanMethods = false)
public class E2eFixtureConfiguration {
    @Bean ApplicationRunner syntheticBrowserAccounts(JdbcTemplate jdbc, PasswordEncoder encoder) {
        return arguments -> {
            insert(jdbc, encoder, value("PIS_E2E_USERNAME", "synthetic.reader"),
                value("PIS_E2E_PASSWORD", "Synthetic-test-only-42!"),
                value("PIS_E2E_DISPLAY_NAME", "合成测试用户"), true);
            insert(jdbc, encoder, value("PIS_E2E_DISABLED_USERNAME", "synthetic.disabled"),
                value("PIS_E2E_DISABLED_PASSWORD", "Synthetic-test-only-42!"), "停用的合成测试用户", false);
        };
    }
    private static void insert(JdbcTemplate jdbc, PasswordEncoder encoder, String username, String password,
                               String displayName, boolean enabled) {
        jdbc.update("""
            INSERT INTO app_user (username, display_name, password_hash, enabled, synthetic_only)
            VALUES (?, ?, ?, ?, true)
            """, username, displayName, encoder.encode(password), enabled);
    }
    private static String value(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
