package com.mafia.classicmafia.entity;

public enum ScopeOverrideDecision {
    INHERIT("Наследовать"),
    ALLOW("Разрешить"),
    DENY("Запретить");

    private final String displayName;

    ScopeOverrideDecision(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
