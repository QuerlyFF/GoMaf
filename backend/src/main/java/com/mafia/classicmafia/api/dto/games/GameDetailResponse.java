package com.mafia.classicmafia.api.dto.games;

import java.time.LocalDate;
import java.util.List;

public record GameDetailResponse(
    Long id,
    String name,
    LocalDate gameDate,
    String host,
    String winner,
    String winnerDisplayName,
    String summary,
    boolean hiddenFromPublic,
    Long tournamentId,
    String tournamentName,
    List<GamePlayerResponse> players,
    List<String> videoLinks,
    List<String> visibilityUsernames,
    boolean manageable
) {
}
