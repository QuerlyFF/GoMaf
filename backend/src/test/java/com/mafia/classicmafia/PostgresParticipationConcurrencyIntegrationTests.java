package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.MafiaFormat;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScheduleEntry;
import com.mafia.classicmafia.entity.ScheduleEntryType;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.ScheduleEntryRepository;
import com.mafia.classicmafia.repository.ScheduleParticipationRepository;
import com.mafia.classicmafia.repository.TournamentParticipantRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.service.ScheduleParticipationService;
import com.mafia.classicmafia.service.TournamentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfSystemProperty(named = "gomaf.postgres.concurrency", matches = "true")
class PostgresParticipationConcurrencyIntegrationTests {
    @Autowired UserRepository users;
    @Autowired ScheduleEntryRepository schedule;
    @Autowired ScheduleParticipationRepository scheduleParticipants;
    @Autowired TournamentParticipantRepository tournamentParticipants;
    @Autowired ScheduleParticipationService scheduleParticipation;
    @Autowired TournamentService tournaments;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void concurrentScheduleAndTournamentTogglesDoNotRaceIntoUniqueViolations() throws Exception {
        User player = new User();
        player.setUsername("pg_race_" + suffix());
        player.setPassword(passwordEncoder.encode("strong-password-123"));
        player.setRole(Role.PLAYER);
        player = users.saveAndFlush(player);
        Long userId = player.getId();

        ScheduleEntry entry = new ScheduleEntry();
        entry.setEntryType(ScheduleEntryType.GAME);
        entry.setTitle("PG race schedule " + suffix());
        entry.setScheduledAt(LocalDateTime.now().plusDays(1));
        entry.setHost("Host");
        entry.setDescription("");
        entry.setRated(false);
        entry.setMafiaFormat(MafiaFormat.CLASSIC);
        entry = schedule.saveAndFlush(entry);
        Long scheduleId = entry.getId();

        var tournament = tournaments.save(
            null,
            "PG race tournament " + suffix(),
            LocalDate.now().plusDays(2).toString(),
            1,
            "",
            ""
        );
        Long tournamentId = tournament.getId();

        runTwo(() -> scheduleParticipation.toggle(
            scheduleId,
            users.findById(userId).orElseThrow()
        ));
        assertThat(scheduleParticipants.countByScheduleEntryId(scheduleId)).isZero();

        runTwo(() -> tournaments.toggleParticipation(
            tournamentId,
            users.findById(userId).orElseThrow()
        ));
        var row = tournamentParticipants.findByTournamentIdAndUserId(tournamentId, userId);
        assertThat(row).isPresent();
        assertThat(row.orElseThrow().isParticipating()).isFalse();
    }

    private void runTwo(ThrowingRunnable operation) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                ready.countDown();
                start.await();
                operation.run();
                return null;
            });
            var second = pool.submit(() -> {
                ready.countDown();
                start.await();
                operation.run();
                return null;
            });
            ready.await();
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
