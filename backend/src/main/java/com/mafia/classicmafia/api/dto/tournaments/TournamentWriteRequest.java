package com.mafia.classicmafia.api.dto.tournaments;

import java.time.LocalDate;
import java.util.List;

public record TournamentWriteRequest(
    String name,
    List<LocalDate> dates,
    int gameCount,
    String participationCost,
    String description
) {
}
