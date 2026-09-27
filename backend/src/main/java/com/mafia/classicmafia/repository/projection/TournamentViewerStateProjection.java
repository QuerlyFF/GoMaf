package com.mafia.classicmafia.repository.projection;

public record TournamentViewerStateProjection(
    boolean invited,
    boolean participating
) {
}
