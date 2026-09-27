package com.mafia.classicmafia.api.dto.tournaments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TournamentDetailResponse(
    Long id,
    String name,
    List<LocalDate> dates,
    int gameCount,
    boolean paid,
    BigDecimal participationCost,
    String description,
    boolean completed,
    long participantCount,
    boolean participating,
    boolean invited,
    boolean manageable,
    List<TournamentParticipantResponse> invitationRows,
    List<TournamentGameResponse> games
) {
}
