package com.mafia.classicmafia.repository.projection;

public record PlayerListProjection(
    Long id,
    String username,
    int gamesPlayed,
    int wins,
    int losses,
    double averageScore,
    double rating
) {
}
