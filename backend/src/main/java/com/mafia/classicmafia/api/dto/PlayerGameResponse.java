package com.mafia.classicmafia.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PlayerGameResponse(
    Long gameId,
    String name,
    LocalDate gameDate,
    String role,
    String roleDisplayName,
    BigDecimal totalPoints,
    BigDecimal additionalPoints,
    boolean won
) {
}
