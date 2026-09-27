package com.mafia.classicmafia.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.CredentialsContainer;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public final class MafiaUserPrincipal implements UserDetails, CredentialsContainer, Serializable {
    @Serial
    private static final long serialVersionUID = 4L;

    private final long userId;
    private final String username;
    private String password;
    private final String roleName;
    private final int credentialVersion;
    private final int authorizationVersion;

    public MafiaUserPrincipal(
        long userId,
        String username,
        String password,
        String roleName,
        int credentialVersion,
        int authorizationVersion
    ) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.roleName = roleName;
        this.credentialVersion = credentialVersion;
        this.authorizationVersion = authorizationVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + roleName));
    }

    @Override public String getPassword() { return password; }
    @Override public void eraseCredentials() { this.password = null; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }

    public long userId() { return userId; }
    public String roleName() { return roleName; }
    public int credentialVersion() { return credentialVersion; }
    public int authorizationVersion() { return authorizationVersion; }
}
