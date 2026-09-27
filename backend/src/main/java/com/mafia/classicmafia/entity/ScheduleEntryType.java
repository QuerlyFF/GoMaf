package com.mafia.classicmafia.entity;

public enum ScheduleEntryType {
    GAME("Игра"),
    EVENT("Мероприятие");

    private final String displayName;

    ScheduleEntryType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
