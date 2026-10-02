package com.pis.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local synthetic account only. No clinical roles, scopes or qualification grants. */
@Component
@Profile("dev & !prod & !test")
@ConditionalOnProperty(name = "pis.security.development-account.enabled", havingValue = "true")
final class DevelopmentAccountBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    DevelopmentAccountBootstrap(Environment environment, JdbcTemplate jdbc, PasswordEncoder encoder,
                                org.springframework.transaction.PlatformTransactionManager manager) {
        this.environment = environment; this.jdbc = jdbc; this.encoder = encoder;
        this.transaction = new TransactionTemplate(manager);
    }
    @Override public void run(ApplicationArguments arguments) {
        if (!"127.0.0.1".equals(environment.getProperty("server.address"))) {
            throw new IllegalStateException("Synthetic development login requires loopback binding");
        }
        String username = environment.getRequiredProperty("PIS_DEV_USERNAME").trim().toLowerCase(Locale.ROOT);
        String password = environment.getRequiredProperty("PIS_DEV_PASSWORD");
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (!username.matches("[a-z0-9][a-z0-9._-]{2,63}") || bytes < 16 || bytes > 72) {
            throw new IllegalStateException("Set a synthetic username and a 16–72-byte development password");
        }
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.endsWith("_dev")) {
            throw new IllegalStateException("Synthetic development login requires a database ending in _dev");
        }
        transaction.executeWithoutResult(status -> {
            var existing = jdbc.query("SELECT synthetic_only FROM app_user WHERE username = ? FOR UPDATE",
                (row, index) -> row.getBoolean(1), username);
            if (!existing.isEmpty()) {
                if (!existing.getFirst()) { throw new IllegalStateException("Refusing to change a non-synthetic account"); }
                return; // Never reset passwords, enable accounts, or replace grants on restart.
            }
            jdbc.update("""
                INSERT INTO app_user (username, display_name, password_hash, enabled, synthetic_only)
                VALUES (?, '本机合成开发用户', ?, true, true)
                """, username, encoder.encode(password));
        });
    }
}
