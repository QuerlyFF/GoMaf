package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.GamePlayer;
import com.mafia.classicmafia.entity.GameRole;
import com.mafia.classicmafia.entity.GameWinner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameScoringTests {
    @Test
    void civiliansWinAndPerfectBestMoveAreCalculatedExactly() {
        List<GamePlayer> players = table();
        GamePlayer firstKilled = players.get(0);
        firstKilled.setFirstKilled(true);
        firstKilled.setBestMove1(8);
        firstKilled.setBestMove2(9);
        firstKilled.setBestMove3(10);
        firstKilled.setBonusPoints(new BigDecimal("0.25"));
        firstKilled.setPenaltyPoints(new BigDecimal("0.10"));

        GameService.calculatePoints(players, GameWinner.CIVILIANS);

        assertThat(firstKilled.isWon()).isTrue();
        assertThat(firstKilled.getVictoryPoints()).isEqualByComparingTo("2.50");
        assertThat(firstKilled.getFirstKilledPoints()).isEqualByComparingTo("0.50");
        assertThat(firstKilled.getBestMovePoints()).isEqualByComparingTo("1.00");
        assertThat(firstKilled.getTotalPoints()).isEqualByComparingTo("4.15");
        assertThat(players.get(7).isWon()).isFalse();
        assertThat(players.get(7).getVictoryPoints()).isEqualByComparingTo("0.00");
    }

    @Test
    void blackTeamGetsVictoryPointsButNoFirstKilledOrBestMoveBonus() {
        List<GamePlayer> players = table();
        GamePlayer mafia = players.get(7);
        mafia.setFirstKilled(true);
        mafia.setBestMove1(8);
        mafia.setBestMove2(9);
        mafia.setBestMove3(10);

        GameService.calculatePoints(players, GameWinner.BLACKS);

        assertThat(mafia.isWon()).isTrue();
        assertThat(mafia.getVictoryPoints()).isEqualByComparingTo("2.50");
        assertThat(mafia.getFirstKilledPoints()).isEqualByComparingTo("0.00");
        assertThat(mafia.getBestMovePoints()).isEqualByComparingTo("0.00");
        assertThat(mafia.getTotalPoints()).isEqualByComparingTo("2.50");
    }

    @Test
    void twoBlackHitsGiveHalfPointAndDrawGivesNoVictoryPoints() {
        List<GamePlayer> players = table();
        GamePlayer firstKilled = players.get(0);
        firstKilled.setFirstKilled(true);
        firstKilled.setBestMove1(2);
        firstKilled.setBestMove2(8);
        firstKilled.setBestMove3(9);

        GameService.calculatePoints(players, GameWinner.DRAW);

        assertThat(firstKilled.isWon()).isFalse();
        assertThat(firstKilled.getVictoryPoints()).isEqualByComparingTo("0.00");
        assertThat(firstKilled.getFirstKilledPoints()).isEqualByComparingTo("0.50");
        assertThat(firstKilled.getBestMovePoints()).isEqualByComparingTo("0.50");
        assertThat(firstKilled.getTotalPoints()).isEqualByComparingTo("1.00");
    }


    @Test
    void repeatedBestMoveSeatIsAcceptedButDoesNotCountTwice() {
        List<GamePlayer> players = table();
        GamePlayer firstKilled = players.get(0);
        firstKilled.setFirstKilled(true);
        firstKilled.setBestMove1(8);
        firstKilled.setBestMove2(8);
        firstKilled.setBestMove3(9);

        GameService.calculatePoints(players, GameWinner.DRAW);

        assertThat(firstKilled.getFirstKilledPoints()).isEqualByComparingTo("0.50");
        assertThat(firstKilled.getBestMovePoints()).isEqualByComparingTo("0.50");
        assertThat(firstKilled.getTotalPoints()).isEqualByComparingTo("1.00");
    }

    private List<GamePlayer> table() {
        List<GamePlayer> players = new ArrayList<>();
        for (int seat = 1; seat <= 10; seat++) {
            GamePlayer player = new GamePlayer();
            player.setSeatNumber(seat);
            player.setDisplayName("P" + seat);
            player.setRole(seat == 8 || seat == 9 ? GameRole.MAFIA : seat == 10 ? GameRole.DON : seat == 7 ? GameRole.SHERIFF : GameRole.CIVILIAN);
            player.setBonusPoints(BigDecimal.ZERO);
            player.setPenaltyPoints(BigDecimal.ZERO);
            players.add(player);
        }
        return players;
    }
}
