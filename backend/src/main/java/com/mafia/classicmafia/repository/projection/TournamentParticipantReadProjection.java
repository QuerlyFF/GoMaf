package com.mafia.classicmafia.repository.projection;

public record TournamentParticipantReadProjection(
    Long userId,
    String username,
    boolean invited,
    boolean participating
) {
}
