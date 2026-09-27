package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.GameRole;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;

import java.math.BigDecimal;

public record GameDetailPlayerProjection(
    int seatNumber,
    Long userId,
    Role userRole,
    String displayName,
    GameRole role,
    boolean firstKilled,
    Integer bestMove1,
    Integer bestMove2,
    Integer bestMove3,
    BigDecimal victoryPoints,
    BigDecimal firstKilledPoints,
    BigDecimal bestMovePoints,
    BigDecimal bonusPoints,
    BigDecimal penaltyPoints,
    BigDecimal totalPoints,
    boolean won
) {
    public Long publicUserId() {
        return userRole != null && userRole.isPublicPlayer() ? userId : null;
    }

    public String publicDisplayName() {
        return userRole == Role.ADMIN || "admin".equals(User.canonicalUsername(displayName))
            ? "Скрытый игрок"
            : displayName;
    }

    public BigDecimal additionalPoints() {
        return firstKilledPoints.add(bestMovePoints).add(bonusPoints).subtract(penaltyPoints);
    }
}
