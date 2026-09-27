package com.mafia.classicmafia.api.dto;

public record PlayerProfileResponse(
    Long id,
    String username,
    String aboutMe,
    int gamesPlayed,
    int wins,
    int losses,
    double winRate,
    double averageScore,
    double rating,
    String avatarUrl,
    String bannerUrl,
    String gamesUrl
) {
}
