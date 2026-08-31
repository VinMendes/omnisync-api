package com.puccampinas.omnisync.core.auth.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Identidade de segurança, sem carregar a entidade JPA para o SecurityContext.
 * O username continua sendo o e-mail para preservar os consumidores atuais de Authentication.getName().
 */
public final class OmniUserPrincipal implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final Long systemClientId;
    private final String name;
    private final String email;
    private final boolean userActive;
    private final boolean systemClientActive;
    private final List<GrantedAuthority> authorities;
    private String passwordHash;

    public OmniUserPrincipal(Long userId, Long systemClientId, String name, String email,
                             String passwordHash, boolean userActive, boolean systemClientActive,
                             Collection<? extends GrantedAuthority> authorities) {
        this.userId = Objects.requireNonNull(userId);
        this.systemClientId = Objects.requireNonNull(systemClientId);
        this.name = name;
        this.email = Objects.requireNonNull(email);
        this.passwordHash = passwordHash;
        this.userActive = userActive;
        this.systemClientActive = systemClientActive;
        this.authorities = List.copyOf(authorities);
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSystemClientId() {
        return systemClientId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public boolean isUserActive() {
        return userActive;
    }

    public boolean isSystemClientActive() {
        return systemClientActive;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isEnabled() {
        return userActive && systemClientActive;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
