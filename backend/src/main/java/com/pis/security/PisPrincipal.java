package com.pis.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Identity only. Role/scope decisions are deliberately not cached in the session. */
public final class PisPrincipal implements UserDetails, CredentialsContainer {
    @Serial private static final long serialVersionUID = 1L;
    private final UUID id;
    private final String username;
    private final String displayName;
    private final long authVersion;
    private final boolean enabled;
    private String password;

    public PisPrincipal(AccountRepository.Account account) {
        id = account.id(); username = account.username(); displayName = account.displayName();
        authVersion = account.authVersion(); enabled = account.enabled(); password = account.passwordHash();
    }
    public UUID id() { return id; }
    public String displayName() { return displayName; }
    public long authVersion() { return authVersion; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
    @Override public void eraseCredentials() { password = null; }
    @Override public boolean equals(Object other) { return other instanceof PisPrincipal principal && id.equals(principal.id); }
    @Override public int hashCode() { return id.hashCode(); }
}
