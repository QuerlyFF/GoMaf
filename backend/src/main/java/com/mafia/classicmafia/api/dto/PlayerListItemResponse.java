package com.mafia.classicmafia.api.dto;

public record PlayerListItemResponse(
    Long id,
    String username,
    int gamesPlayed,
    int wins,
    int losses,
    double winRate,
    double averageScore,
    double rating,
    String avatarUrl
) {
}
