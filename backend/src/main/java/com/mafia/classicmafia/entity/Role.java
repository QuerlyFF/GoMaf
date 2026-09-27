package com.mafia.classicmafia.entity;

public enum Role {
    PLAYER,
    MODERATOR,
    ADMIN;

    public boolean isPublicPlayer() {
        return this == PLAYER || this == MODERATOR;
    }
}
