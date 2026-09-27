package com.mafia.classicmafia.repository.projection;

import java.math.BigDecimal;

public record RankingSourceProjection(
    Long userId,
    String username,
    String displayName,
    boolean won,
    BigDecimal totalPoints,
    BigDecimal firstKilledPoints,
    BigDecimal bestMovePoints,
    BigDecimal bonusPoints,
    BigDecimal penaltyPoints
) {
    public BigDecimal additionalPoints() {
        return firstKilledPoints.add(bestMovePoints).add(bonusPoints).subtract(penaltyPoints);
    }
}
