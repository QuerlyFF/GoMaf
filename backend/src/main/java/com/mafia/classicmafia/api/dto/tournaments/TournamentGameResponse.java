package com.mafia.classicmafia.api.dto.tournaments;

import java.time.LocalDate;

public record TournamentGameResponse(Long id, String name, LocalDate gameDate, String host, boolean hiddenFromPublic) {
}
