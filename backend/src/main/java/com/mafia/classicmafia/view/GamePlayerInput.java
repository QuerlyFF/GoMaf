package com.mafia.classicmafia.view;

import java.math.BigDecimal;

public record GamePlayerInput(
    int seatNumber,
    String registeredUserId,
    String customNickname,
    String role,
    boolean firstKilled,
    String bestMove1,
    String bestMove2,
    String bestMove3,
    String bonusPoints,
    String penaltyPoints
) {
    public BigDecimal parsedBonusPoints() {
        return parseDecimal(bonusPoints);
    }

    public BigDecimal parsedPenaltyPoints() {
        return parseDecimal(penaltyPoints);
    }

    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Дополнительные и штрафные баллы должны быть числами.");
        }
    }
}
