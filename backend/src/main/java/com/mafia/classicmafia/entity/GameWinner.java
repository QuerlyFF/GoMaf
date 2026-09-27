package com.mafia.classicmafia.entity;

public enum GameWinner {
    CIVILIANS("Мирные"),
    BLACKS("Чёрные"),
    DRAW("Ничья");

    private final String displayName;

    GameWinner(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
