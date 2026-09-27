package com.mafia.classicmafia.api.dto.tournaments;

public record TournamentParticipantResponse(Long userId, String username, boolean invited, boolean participating) {
}
