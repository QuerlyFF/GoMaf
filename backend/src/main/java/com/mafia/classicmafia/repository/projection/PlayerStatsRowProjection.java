package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.GameWinner;

import java.math.BigDecimal;

public record PlayerStatsRowProjection(
    Long userId,
    boolean won,
    GameWinner winner,
    BigDecimal firstKilledPoints,
    BigDecimal bestMovePoints,
    BigDecimal bonusPoints,
    BigDecimal penaltyPoints
) {
    public BigDecimal additionalPoints() {
        return firstKilledPoints.add(bestMovePoints).add(bonusPoints).subtract(penaltyPoints);
    }
}
