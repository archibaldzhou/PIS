package com.pis.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Account> findByUsername(String username) {
        return jdbc.query("""
            SELECT id, username, display_name, password_hash, enabled, auth_version
            FROM app_user WHERE username = ?
            """, (row, index) -> new Account(row.getObject("id", UUID.class),
                row.getString("username"), row.getString("display_name"), row.getString("password_hash"),
                row.getBoolean("enabled"), row.getLong("auth_version")), username).stream().findFirst();
    }

    public Optional<AccountState> state(UUID id) {
        return jdbc.query("SELECT enabled, auth_version, password_change_required FROM app_user WHERE id = ?",
            (row, index) -> new AccountState(row.getBoolean("enabled"), row.getLong("auth_version"), row.getBoolean("password_change_required")),
            id).stream().findFirst();
    }

    public record Account(UUID id, String username, String displayName, String passwordHash,
                          boolean enabled, long authVersion) { }
    public record AccountState(boolean enabled, long authVersion, boolean passwordChangeRequired) { }
}
