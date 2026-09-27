package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.GameWinner;

import java.time.LocalDate;

public record GameDetailProjection(
    Long id,
    String name,
    LocalDate gameDate,
    String host,
    GameWinner winner,
    String summary,
    boolean hiddenFromPublic,
    Long tournamentId,
    String tournamentName
) {
}
