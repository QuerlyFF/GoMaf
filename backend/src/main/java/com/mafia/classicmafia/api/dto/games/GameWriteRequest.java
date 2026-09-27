package com.mafia.classicmafia.api.dto.games;

import java.time.LocalDate;
import java.util.List;

public record GameWriteRequest(
    String name,
    LocalDate gameDate,
    String host,
    String winner,
    String summary,
    List<String> videoLinks,
    Long tournamentId,
    boolean hiddenFromPublic,
    List<String> visibilityUsernames,
    List<GamePlayerWriteRequest> players
) {
}
