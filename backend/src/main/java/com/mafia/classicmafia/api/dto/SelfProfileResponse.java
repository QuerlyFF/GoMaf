package com.mafia.classicmafia.api.dto;

public record SelfProfileResponse(
    Long id,
    String username,
    String role,
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
