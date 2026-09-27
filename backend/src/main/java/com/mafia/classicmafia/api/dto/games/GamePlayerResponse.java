package com.mafia.classicmafia.api.dto.games;

import java.math.BigDecimal;

public record GamePlayerResponse(
    int seatNumber,
    Long userId,
    String displayName,
    String role,
    String roleDisplayName,
    boolean firstKilled,
    Integer bestMove1,
    Integer bestMove2,
    Integer bestMove3,
    BigDecimal victoryPoints,
    BigDecimal firstKilledPoints,
    BigDecimal bestMovePoints,
    BigDecimal bonusPoints,
    BigDecimal penaltyPoints,
    BigDecimal additionalPoints,
    BigDecimal totalPoints,
    boolean won
) {
}
