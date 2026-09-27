package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.GameRole;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PlayerGameProjection(
    Long gameId,
    String name,
    LocalDate gameDate,
    GameRole role,
    BigDecimal totalPoints,
    BigDecimal firstKilledPoints,
    BigDecimal bestMovePoints,
    BigDecimal bonusPoints,
    BigDecimal penaltyPoints,
    boolean won
) {
    public BigDecimal additionalPoints() {
        return firstKilledPoints.add(bestMovePoints).add(bonusPoints).subtract(penaltyPoints);
    }
}
