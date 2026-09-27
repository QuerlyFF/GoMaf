package com.mafia.classicmafia.api.dto.rating;

import java.math.BigDecimal;

public record RatingRowResponse(
    int rank,
    String displayName,
    Long userId,
    int games,
    int wins,
    BigDecimal totalPoints,
    BigDecimal averageAdditionalPoints,
    double winRate,
    BigDecimal weightedScore
) {
}
