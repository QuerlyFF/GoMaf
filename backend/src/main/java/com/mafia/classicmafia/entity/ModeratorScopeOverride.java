package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "moderator_scope_overrides",
    uniqueConstraints = @UniqueConstraint(name = "uq_moderator_scope_override_user_scope", columnNames = {"user_id", "scope"})
)
public class ModeratorScopeOverride {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private ModeratorScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScopeOverrideDecision decision = ScopeOverrideDecision.INHERIT;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public ModeratorScope getScope() {
        return scope;
    }

    public void setScope(ModeratorScope scope) {
        this.scope = scope;
    }

    public ScopeOverrideDecision getDecision() {
        return decision;
    }

    public void setDecision(ScopeOverrideDecision decision) {
        this.decision = decision;
    }
}
