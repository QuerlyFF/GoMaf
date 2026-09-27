package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "moderator_scope_defaults")
public class ModeratorScopeDefault {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private ModeratorScope scope;

    @Column(nullable = false)
    private boolean enabled;

    public ModeratorScope getScope() {
        return scope;
    }

    public void setScope(ModeratorScope scope) {
        this.scope = scope;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
