package com.mafia.classicmafia.api.dto;

public record PlayerAdministrationActionResponse(
    Long playerId,
    String username,
    String message
) {
}
