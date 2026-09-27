package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "game_players")
public class GamePlayer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private int seatNumber;

    @Column(nullable = false, length = 64)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GameRole role = GameRole.CIVILIAN;

    @Column(nullable = false)
    private boolean firstKilled;

    private Integer bestMove1;
    private Integer bestMove2;
    private Integer bestMove3;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal victoryPoints = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal firstKilledPoints = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal bestMovePoints = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal bonusPoints = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal penaltyPoints = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal totalPoints = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean won;

    public Long getId() {
        return id;
    }

    public Game getGame() {
        return game;
    }

    public void setGame(Game game) {
        this.game = game;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(int seatNumber) {
        this.seatNumber = seatNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Public rendering must never disclose the singleton ADMIN through historical
     * game rows. The stored displayName is kept intact for audit/history and for
     * rollback compatibility; only the public presentation is anonymized.
     */
    public String getPublicDisplayName() {
        if (user != null && user.getRole() == Role.ADMIN) {
            return "Скрытый игрок";
        }
        return "admin".equals(User.canonicalUsername(displayName)) ? "Скрытый игрок" : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public GameRole getRole() {
        return role;
    }

    public void setRole(GameRole role) {
        this.role = role;
    }

    public boolean isFirstKilled() {
        return firstKilled;
    }

    public void setFirstKilled(boolean firstKilled) {
        this.firstKilled = firstKilled;
    }

    public Integer getBestMove1() {
        return bestMove1;
    }

    public void setBestMove1(Integer bestMove1) {
        this.bestMove1 = bestMove1;
    }

    public Integer getBestMove2() {
        return bestMove2;
    }

    public void setBestMove2(Integer bestMove2) {
        this.bestMove2 = bestMove2;
    }

    public Integer getBestMove3() {
        return bestMove3;
    }

    public void setBestMove3(Integer bestMove3) {
        this.bestMove3 = bestMove3;
    }

    public BigDecimal getVictoryPoints() {
        return victoryPoints;
    }

    public void setVictoryPoints(BigDecimal victoryPoints) {
        this.victoryPoints = victoryPoints;
    }

    public BigDecimal getFirstKilledPoints() {
        return firstKilledPoints;
    }

    public void setFirstKilledPoints(BigDecimal firstKilledPoints) {
        this.firstKilledPoints = firstKilledPoints;
    }

    public BigDecimal getBestMovePoints() {
        return bestMovePoints;
    }

    public void setBestMovePoints(BigDecimal bestMovePoints) {
        this.bestMovePoints = bestMovePoints;
    }

    public BigDecimal getBonusPoints() {
        return bonusPoints;
    }

    public void setBonusPoints(BigDecimal bonusPoints) {
        this.bonusPoints = bonusPoints;
    }

    public BigDecimal getPenaltyPoints() {
        return penaltyPoints;
    }

    public void setPenaltyPoints(BigDecimal penaltyPoints) {
        this.penaltyPoints = penaltyPoints;
    }

    public BigDecimal getTotalPoints() {
        return totalPoints;
    }

    public BigDecimal getAdditionalPoints() {
        return firstKilledPoints.add(bestMovePoints).add(bonusPoints).subtract(penaltyPoints);
    }

    public void setTotalPoints(BigDecimal totalPoints) {
        this.totalPoints = totalPoints;
    }

    public boolean isWon() {
        return won;
    }

    public void setWon(boolean won) {
        this.won = won;
    }

    public String getBestMoveFormatted() {
        if (bestMove1 == null || bestMove2 == null || bestMove3 == null) {
            return "—";
        }
        return bestMove1 + ", " + bestMove2 + ", " + bestMove3;
    }
}
