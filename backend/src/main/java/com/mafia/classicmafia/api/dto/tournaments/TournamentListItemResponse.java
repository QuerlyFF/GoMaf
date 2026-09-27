package com.mafia.classicmafia.api.dto.tournaments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TournamentListItemResponse(
    Long id,
    String name,
    List<LocalDate> dates,
    int gameCount,
    boolean paid,
    BigDecimal participationCost,
    boolean completed
) {
}
