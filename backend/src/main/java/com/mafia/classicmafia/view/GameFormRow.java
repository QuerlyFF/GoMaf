package com.mafia.classicmafia.view;

import java.math.BigDecimal;

public class GameFormRow {
    private final int seatNumber;
    private final Long registeredUserId;
    private final String customNickname;
    private final String role;
    private final boolean firstKilled;
    private final Integer bestMove1;
    private final Integer bestMove2;
    private final Integer bestMove3;
    private final BigDecimal bonusPoints;
    private final BigDecimal penaltyPoints;

    public GameFormRow(
        int seatNumber,
        Long registeredUserId,
        String customNickname,
        String role,
        boolean firstKilled,
        Integer bestMove1,
        Integer bestMove2,
        Integer bestMove3,
        BigDecimal bonusPoints,
        BigDecimal penaltyPoints
    ) {
        this.seatNumber = seatNumber;
        this.registeredUserId = registeredUserId;
        this.customNickname = customNickname;
        this.role = role;
        this.firstKilled = firstKilled;
        this.bestMove1 = bestMove1;
        this.bestMove2 = bestMove2;
        this.bestMove3 = bestMove3;
        this.bonusPoints = bonusPoints;
        this.penaltyPoints = penaltyPoints;
    }

    public int getSeatNumber() { return seatNumber; }
    public Long getRegisteredUserId() { return registeredUserId; }
    public String getCustomNickname() { return customNickname; }
    public String getRole() { return role; }
    public boolean isFirstKilled() { return firstKilled; }
    public Integer getBestMove1() { return bestMove1; }
    public Integer getBestMove2() { return bestMove2; }
    public Integer getBestMove3() { return bestMove3; }
    public BigDecimal getBonusPoints() { return bonusPoints; }
    public BigDecimal getPenaltyPoints() { return penaltyPoints; }
}
