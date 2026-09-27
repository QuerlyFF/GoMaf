package com.mafia.classicmafia.entity;

public enum GameRole {
    CIVILIAN("Мирный житель"),
    MAFIA("Мафия"),
    DON("Дон"),
    SHERIFF("Шериф");

    private final String displayName;

    GameRole(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isBlackTeam() {
        return this == MAFIA || this == DON;
    }
}
