package com.mafia.classicmafia.repository.projection;

import java.time.LocalDate;

public record TournamentGameReadProjection(
    Long id,
    String name,
    LocalDate gameDate,
    String host,
    boolean hiddenFromPublic
) {
}
