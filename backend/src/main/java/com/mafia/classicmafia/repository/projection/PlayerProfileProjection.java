package com.mafia.classicmafia.repository.projection;

public record PlayerProfileProjection(
    Long id,
    String username,
    String aboutMe,
    int gamesPlayed,
    int wins,
    int losses,
    double averageScore,
    double rating
) {
}
