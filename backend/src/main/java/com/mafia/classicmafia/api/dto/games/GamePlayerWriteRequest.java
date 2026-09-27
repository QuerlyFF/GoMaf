package com.mafia.classicmafia.api.dto.games;

public record GamePlayerWriteRequest(
    int seatNumber,
    String username,
    String role,
    boolean firstKilled,
    Integer bestMove1,
    Integer bestMove2,
    Integer bestMove3,
    String bonusPoints,
    String penaltyPoints
) {
}
