package com.mafia.classicmafia.view;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class GameRankingRow {
    private final String displayName;
    private final Long userId;
    private final int games;
    private final int wins;
    private final BigDecimal totalPoints;
    private final BigDecimal averageAdditionalPoints;
    private final double winRate;
    private final BigDecimal weightedScore;

    public GameRankingRow(
        String displayName,
        Long userId,
        int games,
        int wins,
        BigDecimal totalPoints,
        BigDecimal averageAdditionalPoints,
        double winRate,
        BigDecimal weightedScore
    ) {
        this.displayName = displayName;
        this.userId = userId;
        this.games = games;
        this.wins = wins;
        this.totalPoints = totalPoints;
        this.averageAdditionalPoints = averageAdditionalPoints;
        this.winRate = winRate;
        this.weightedScore = weightedScore;
    }

    public String getDisplayName() { return displayName; }
    public Long getUserId() { return userId; }
    public int getGames() { return games; }
    public int getWins() { return wins; }
    public BigDecimal getTotalPoints() { return totalPoints; }
    public BigDecimal getAverageAdditionalPoints() { return averageAdditionalPoints; }
    public double getWinRate() { return winRate; }
    public BigDecimal getWeightedScore() { return weightedScore; }

    public String getWinRateFormatted() { return String.format(java.util.Locale.ROOT, "%.1f%%", winRate * 100.0); }
    public String getTotalPointsFormatted() { return format(totalPoints); }
    public String getAverageAdditionalPointsFormatted() { return format(averageAdditionalPoints); }
    public String getWeightedScoreFormatted() { return format(weightedScore); }

    private String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
