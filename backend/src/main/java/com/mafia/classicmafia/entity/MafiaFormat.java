package com.mafia.classicmafia.entity;

public enum MafiaFormat {
    CLASSIC("Классическая мафия"),
    CITY("Городская мафия");

    private final String displayName;

    MafiaFormat(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
