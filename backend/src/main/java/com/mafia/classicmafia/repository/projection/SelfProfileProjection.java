package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.Role;

public record SelfProfileProjection(
    Long id,
    String username,
    Role role,
    String aboutMe,
    int gamesPlayed,
    int wins,
    int losses,
    double averageScore,
    double rating
) {
}
