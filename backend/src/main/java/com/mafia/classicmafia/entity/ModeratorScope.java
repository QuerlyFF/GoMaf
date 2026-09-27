package com.mafia.classicmafia.entity;

public enum ModeratorScope {
    NEWS_MANAGE("Новости"),
    RULES_MANAGE("Правила"),
    GAMES_MANAGE("Игры"),
    SCHEDULE_MANAGE("Расписание"),
    PLAYER_RATING_MANAGE("Рейтинг игроков"),
    PLAYER_USERNAME_MANAGE("Ники игроков"),
    PLAYER_AVATAR_RESET("Сброс аватаров"),
    PLAYER_PASSWORD_RESET("Сброс паролей игроков"),
    PLAYER_DELETE("Удаление аккаунтов игроков");

    private final String displayName;

    ModeratorScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
