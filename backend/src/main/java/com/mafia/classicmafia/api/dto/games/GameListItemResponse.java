package com.mafia.classicmafia.api.dto.games;

import java.time.LocalDate;

public record GameListItemResponse(
    Long id,
    String name,
    LocalDate gameDate,
    String host,
    String winner,
    String winnerDisplayName,
    Long tournamentId,
    String tournamentName
) {
}
