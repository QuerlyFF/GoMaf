package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.Tournament;
import com.mafia.classicmafia.entity.TournamentParticipant;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.TournamentParticipantRepository;
import com.mafia.classicmafia.repository.TournamentRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.TournamentGameReadProjection;
import com.mafia.classicmafia.repository.projection.TournamentParticipantReadProjection;
import com.mafia.classicmafia.repository.projection.TournamentViewerStateProjection;
import com.mafia.classicmafia.security.DatabaseLockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TournamentService {
    private final TournamentRepository tournaments;
    private final TournamentParticipantRepository participants;
    private final UserRepository users;
    private final GameRepository games;
    private final DatabaseLockService databaseLocks;

    public TournamentService(
        TournamentRepository tournaments,
        TournamentParticipantRepository participants,
        UserRepository users,
        GameRepository games,
        DatabaseLockService databaseLocks
    ) {
        this.tournaments = tournaments;
        this.participants = participants;
        this.users = users;
        this.games = games;
        this.databaseLocks = databaseLocks;
    }

    @Transactional(readOnly = true)
    public List<Tournament> list(boolean completed) {
        return tournaments.findAllDetailedByOrderByCreatedAtDesc().stream()
            .filter(item -> item.isCompleted() == completed)
            .toList();
    }

    @Transactional(readOnly = true)
    public Tournament require(Long id) {
        return tournaments.findDetailedById(id).orElseThrow(() -> new IllegalArgumentException("Турнир не найден."));
    }

    @Transactional
    public Tournament save(Long id, String name, String datesText, int gameCount, String participationCost, String description) {
        String normalizedName = normalize(name);
        String normalizedDescription = description == null ? "" : description.strip();
        if (normalizedName.isBlank() || normalizedName.length() > 160) {
            throw new IllegalArgumentException("Название турнира обязательно и должно быть не длиннее 160 символов.");
        }
        if (gameCount < 1 || gameCount > 1000) {
            throw new IllegalArgumentException("Количество игр должно быть от 1 до 1000.");
        }
        if (normalizedDescription.length() > 20_000) {
            throw new IllegalArgumentException("Описание турнира слишком длинное.");
        }
        List<LocalDate> dates = parseDates(datesText);
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("Укажите хотя бы одну дату турнира.");
        }
        BigDecimal cost = parseCost(participationCost);

        Tournament tournament = id == null ? new Tournament() : require(id);
        tournament.setName(normalizedName);
        tournament.setGameCount(gameCount);
        tournament.setParticipationCost(cost);
        tournament.setDescription(normalizedDescription);
        tournament.replaceDates(dates);
        return tournaments.saveAndFlush(tournament);
    }

    @Transactional
    public void delete(Long id) {
        Tournament tournament = require(id);
        if (games.existsByTournamentId(id)) {
            throw new IllegalArgumentException("Сначала удалите игры турнира.");
        }
        tournaments.delete(tournament);
    }

    @Transactional
    public boolean toggleParticipation(Long tournamentId, User user) {
        if (!user.getRole().isPublicPlayer()) {
            throw new IllegalArgumentException("Системный аккаунт не может участвовать в турнире.");
        }
        Tournament tournament = require(tournamentId);
        databaseLocks.lock("tournament-participant", tournamentId + ":" + user.getId());
        TournamentParticipant record = participants.findByTournamentIdAndUserId(tournamentId, user.getId()).orElse(null);
        if (record != null && record.isParticipating()) {
            record.setParticipating(false);
            participants.save(record);
            return false;
        }
        if (tournament.isPaid() && (record == null || !record.isInvited())) {
            throw new IllegalArgumentException("Для участия в платном турнире требуется приглашение.");
        }
        if (record == null) {
            record = new TournamentParticipant();
            record.setTournament(tournament);
            record.setUser(user);
            record.setInvited(false);
        }
        record.setParticipating(true);
        participants.save(record);
        return true;
    }

    @Transactional
    public User invite(Long tournamentId, String username) {
        Tournament tournament = require(tournamentId);
        String key = User.canonicalUsername(username);
        User user = users.findByUsernameKey(key)
            .filter(item -> item.getRole().isPublicPlayer())
            .orElseThrow(() -> new IllegalArgumentException("Зарегистрированный игрок с таким ником не найден."));
        databaseLocks.lock("tournament-participant", tournamentId + ":" + user.getId());
        TournamentParticipant record = participants.findByTournamentIdAndUserId(tournamentId, user.getId()).orElse(null);
        if (record == null) {
            record = new TournamentParticipant();
            record.setTournament(tournament);
            record.setUser(user);
        }
        record.setInvited(true);
        participants.save(record);
        return user;
    }

    @Transactional
    public void removeInvitation(Long tournamentId, Long userId) {
        Tournament tournament = require(tournamentId);
        databaseLocks.lock("tournament-participant", tournamentId + ":" + userId);
        participants.findByTournamentIdAndUserId(tournamentId, userId).ifPresent(record -> {
            if (tournament.isPaid()) {
                participants.delete(record);
            } else if (record.isParticipating()) {
                record.setInvited(false);
                participants.save(record);
            } else {
                participants.delete(record);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<TournamentParticipantReadProjection> participantRows(Long tournamentId) {
        return participants.findReadRows(tournamentId);
    }

    @Transactional(readOnly = true)
    public TournamentViewerStateProjection viewerState(Long tournamentId, Long userId) {
        if (userId == null) {
            return new TournamentViewerStateProjection(false, false);
        }
        return participants.findViewerState(tournamentId, userId)
            .orElse(new TournamentViewerStateProjection(false, false));
    }

    @Transactional(readOnly = true)
    public long participantCount(Long tournamentId) {
        return participants.countByTournamentIdAndParticipatingTrue(tournamentId);
    }

    @Transactional(readOnly = true)
    public List<TournamentGameReadProjection> visibleGames(Tournament tournament, Long viewerId, boolean admin) {
        List<TournamentGameReadProjection> rows = games.findTournamentReadRows(tournament.getId());
        if (admin) {
            return rows;
        }
        if (viewerId == null) {
            return rows.stream().filter(game -> !game.hiddenFromPublic()).toList();
        }
        Set<Long> visibleHiddenIds = new HashSet<>(games.findTournamentVisibleGameIds(tournament.getId(), viewerId));
        return rows.stream()
            .filter(game -> !game.hiddenFromPublic() || visibleHiddenIds.contains(game.id()))
            .toList();
    }

    private List<LocalDate> parseDates(String text) {
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        String normalized = text == null ? "" : text.replace(',', '\n').replace(';', '\n');
        for (String raw : normalized.split("\\R")) {
            String value = raw.strip();
            if (value.isEmpty()) continue;
            try {
                dates.add(LocalDate.parse(value));
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Дата турнира должна быть в формате ГГГГ-ММ-ДД: " + value);
            }
        }
        return new ArrayList<>(dates);
    }

    private BigDecimal parseCost(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal cost = new BigDecimal(value.replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
            if (cost.signum() < 0 || cost.compareTo(new BigDecimal("99999999.99")) > 0) {
                throw new IllegalArgumentException("Некорректная стоимость участия.");
            }
            return cost.signum() == 0 ? null : cost;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Некорректная стоимость участия.");
        }
    }

    private String normalize(String value) { return value == null ? "" : value.strip(); }
}
