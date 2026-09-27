package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "persistent_login_tokens")
public class PersistentLoginToken {
    @Id
    @Column(length = 64)
    private String selector;

    @Column(name = "validator_hash", nullable = false, length = 64)
    private String validatorHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_name", nullable = false, length = 16)
    private String roleName;

    @Column(name = "credential_version", nullable = false)
    private int credentialVersion;

    @Column(name = "authorization_version", nullable = false)
    private int authorizationVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }
    public String getValidatorHash() { return validatorHash; }
    public void setValidatorHash(String validatorHash) { this.validatorHash = validatorHash; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public int getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(int credentialVersion) { this.credentialVersion = credentialVersion; }
    public int getAuthorizationVersion() { return authorizationVersion; }
    public void setAuthorizationVersion(int authorizationVersion) { this.authorizationVersion = authorizationVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
