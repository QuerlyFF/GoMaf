package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.Game;
import com.mafia.classicmafia.entity.GamePlayer;
import com.mafia.classicmafia.entity.GameRole;
import com.mafia.classicmafia.entity.GameWinner;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.Tournament;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.GamePlayerRepository;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.GameVideoRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.GameDetailPlayerProjection;
import com.mafia.classicmafia.repository.projection.GameDetailProjection;
import com.mafia.classicmafia.repository.projection.PlayerStatsRowProjection;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import com.mafia.classicmafia.view.GamePlayerInput;
import com.mafia.classicmafia.view.GameRankingRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class GameService {
    private static final BigDecimal WIN_POINTS = new BigDecimal("2.50");
    private static final BigDecimal FIRST_KILLED_POINTS = new BigDecimal("0.50");
    private static final BigDecimal BEST_MOVE_TWO_POINTS = new BigDecimal("0.50");
    private static final BigDecimal BEST_MOVE_THREE_POINTS = new BigDecimal("1.00");

    private final GameRepository games;
    private final GamePlayerRepository gamePlayers;
    private final GameVideoRepository gameVideos;
    private final UserRepository users;

    public GameService(GameRepository games, GamePlayerRepository gamePlayers, GameVideoRepository gameVideos, UserRepository users) {
        this.games = games;
        this.gamePlayers = gamePlayers;
        this.gameVideos = gameVideos;
        this.users = users;
    }

    public List<Game> listGames() { return games.findAllByOrderByGameDateDescIdDesc(); }

    @Transactional(readOnly = true)
    public Page<Game> searchGames(String query, Pageable pageable) {
        return games.searchPublic(query == null ? "" : query.strip(), pageable);
    }

    @Transactional(readOnly = true)
    public GameDetailRead getDetailRead(Long id, SecurityAccountProjection viewer, boolean manageable) {
        GameDetailProjection game = games.findDetailProjectionById(id)
            .orElseThrow(() -> new IllegalArgumentException("Игра не найдена."));
        if (game.hiddenFromPublic()) {
            if (viewer == null) throw new IllegalArgumentException("Игра недоступна.");
            if (viewer.role() != Role.ADMIN && games.countVisibilityUser(id, viewer.id()) == 0) {
                throw new IllegalArgumentException("Игра недоступна.");
            }
        }
        List<String> visibility = manageable
            ? games.findVisibilityUsernames(id, List.of(Role.PLAYER, Role.MODERATOR))
            : List.of();
        return new GameDetailRead(
            game,
            gamePlayers.findDetailRowsByGameId(id),
            gameVideos.findUrlsByGameId(id),
            visibility
        );
    }

    @Transactional(readOnly = true)
    public Game getDetailed(Long id) {
        Game game = games.findDetailedById(id).orElseThrow(() -> new IllegalArgumentException("Игра не найдена."));
        game.getVideos().size();
        game.getHiddenAllowedUsers().size();
        if (game.getTournament() != null) game.getTournament().getName();
        return game;
    }

    @Transactional(readOnly = true)
    public Game getDetailedVisible(Long id, User viewer) {
        Game game = getDetailed(id);
        if (!canView(game, viewer)) throw new IllegalArgumentException("Игра недоступна.");
        return game;
    }

    public boolean canView(Game game, User viewer) {
        if (!game.isHiddenFromPublic()) return true;
        if (viewer != null && viewer.getRole() == Role.ADMIN) return true;
        return viewer != null && game.getHiddenAllowedUsers().stream().anyMatch(user -> user.getId().equals(viewer.getId()));
    }

    @Transactional
    public Game saveGame(
        Long id,
        String name,
        LocalDate gameDate,
        String host,
        GameWinner winner,
        String summary,
        List<GamePlayerInput> inputs,
        String videoLinksText,
        Tournament tournament,
        boolean hiddenFromPublic,
        String visibilityUsernames
    ) {
        String normalizedName = normalize(name);
        String normalizedHost = normalize(host);
        String normalizedSummary = summary == null ? "" : summary.strip();
        if (normalizedName.isBlank() || normalizedName.length() > 120) throw new IllegalArgumentException("Название игры обязательно и должно быть не длиннее 120 символов.");
        if (gameDate == null) throw new IllegalArgumentException("Укажите дату игры.");
        if (normalizedHost.isBlank() || normalizedHost.length() > 80) throw new IllegalArgumentException("Укажите ведущего.");
        if (winner == null) throw new IllegalArgumentException("Укажите победившую сторону.");
        if (normalizedSummary.length() > 10_000) throw new IllegalArgumentException("Краткая статистика слишком длинная.");
        if (hiddenFromPublic && tournament == null) throw new IllegalArgumentException("Скрывать можно только игру внутри турнира.");

        List<GamePlayer> parsedPlayers = parsePlayers(inputs);
        calculatePoints(parsedPlayers, winner);
        List<String> videoLinks = parseVideoLinks(videoLinksText);
        List<User> visibleUsers = hiddenFromPublic ? parseVisibilityUsers(visibilityUsernames) : List.of();

        Set<Long> affectedPlayerIds = id == null
            ? new HashSet<>()
            : new HashSet<>(gamePlayers.findRegisteredUserIdsByGameId(id));
        parsedPlayers.stream()
            .map(GamePlayer::getUser)
            .filter(java.util.Objects::nonNull)
            .map(User::getId)
            .forEach(affectedPlayerIds::add);

        Game game = id == null ? new Game() : games.findDetailedById(id).orElseThrow(() -> new IllegalArgumentException("Игра не найдена."));
        if (id != null && tournament != null && game.getTournament() != null && !game.getTournament().getId().equals(tournament.getId())) {
            throw new IllegalArgumentException("Игра принадлежит другому турниру.");
        }
        game.setName(normalizedName);
        game.setGameDate(gameDate);
        game.setHost(normalizedHost);
        game.setWinner(winner);
        game.setSummary(normalizedSummary);
        game.setTournament(tournament);
        game.setHiddenFromPublic(hiddenFromPublic);
        game.clearPlayers();
        for (GamePlayer player : parsedPlayers) game.addPlayer(player);
        game.replaceVideos(videoLinks);
        game.replaceHiddenAllowedUsers(visibleUsers);
        Game saved = games.saveAndFlush(game);
        recalculateRegisteredPlayerStats(affectedPlayerIds);
        return saved;
    }

    @Transactional
    public void deleteGame(Long id) {
        if (!games.existsById(id)) return;
        Set<Long> affectedPlayerIds = new HashSet<>(gamePlayers.findRegisteredUserIdsByGameId(id));
        games.deleteById(id);
        games.flush();
        recalculateRegisteredPlayerStats(affectedPlayerIds);
    }

    @Transactional(readOnly = true)
    public List<GameRankingRow> buildRanking(String sort) {
        Map<String, MutableRanking> aggregate = new LinkedHashMap<>();
        for (var player : gamePlayers.findPublicRankingRows(List.of(Role.PLAYER, Role.MODERATOR))) {
            String key;
            String name;
            Long userId;
            if (player.userId() != null) {
                key = "u:" + player.userId(); name = player.username(); userId = player.userId();
            } else {
                name = player.displayName();
                if ("admin".equals(User.canonicalUsername(name))) continue;
                key = "n:" + name.toLowerCase(Locale.ROOT); userId = null;
            }
            MutableRanking row = aggregate.computeIfAbsent(key, ignored -> new MutableRanking(name, userId));
            row.games++;
            if (player.won()) row.wins++;
            row.totalPoints = row.totalPoints.add(player.totalPoints());
            row.additionalPoints = row.additionalPoints.add(player.additionalPoints());
        }
        List<GameRankingRow> rows = new ArrayList<>();
        for (MutableRanking row : aggregate.values()) {
            double winRate = row.games == 0 ? 0.0 : (double) row.wins / row.games;
            BigDecimal weighted = row.totalPoints.multiply(BigDecimal.valueOf(winRate));
            BigDecimal averageAdditional = row.games == 0 ? BigDecimal.ZERO : row.additionalPoints.divide(BigDecimal.valueOf(row.games), 4, RoundingMode.HALF_UP);
            rows.add(new GameRankingRow(row.name, row.userId, row.games, row.wins, row.totalPoints, averageAdditional, winRate, weighted));
        }
        Comparator<GameRankingRow> comparator = "weighted".equalsIgnoreCase(sort)
            ? Comparator.comparing(GameRankingRow::getWeightedScore).reversed().thenComparing(GameRankingRow::getTotalPoints, Comparator.reverseOrder()).thenComparing(GameRankingRow::getDisplayName, String.CASE_INSENSITIVE_ORDER)
            : Comparator.comparing(GameRankingRow::getTotalPoints).reversed().thenComparing(GameRankingRow::getWeightedScore, Comparator.reverseOrder()).thenComparing(GameRankingRow::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        rows.sort(comparator);
        return rows;
    }

    private List<GamePlayer> parsePlayers(List<GamePlayerInput> inputs) {
        List<GamePlayer> result = new ArrayList<>();
        Set<Integer> seats = new HashSet<>();
        Set<String> identities = new HashSet<>();
        int firstKilledCount = 0;
        for (GamePlayerInput input : inputs) {
            boolean hasRegistered = input.registeredUserId() != null && !input.registeredUserId().isBlank();
            boolean hasCustom = input.customNickname() != null && !input.customNickname().isBlank();
            if (!hasRegistered && !hasCustom) continue;
            if (input.seatNumber() < 1 || input.seatNumber() > 10 || !seats.add(input.seatNumber())) throw new IllegalArgumentException("Номера игроков должны быть уникальными и находиться в диапазоне 1–10.");
            User linkedUser = null;
            String displayName;
            if (hasRegistered) {
                long userId = parseLong(input.registeredUserId(), "Некорректный выбранный игрок.");
                linkedUser = users.findById(userId).filter(user -> user.getRole().isPublicPlayer()).orElseThrow(() -> new IllegalArgumentException("Выбранный зарегистрированный игрок не найден."));
                displayName = linkedUser.getUsername();
            } else {
                displayName = normalize(input.customNickname());
                if (displayName.isBlank() || displayName.length() > 64) throw new IllegalArgumentException("Ник игрока должен быть от 1 до 64 символов.");
                if ("admin".equals(User.canonicalUsername(displayName))) throw new IllegalArgumentException("Ник admin зарезервирован системой и не может использоваться как участник игры.");
                linkedUser = users.findByUsernameKey(User.canonicalUsername(displayName)).filter(user -> user.getRole().isPublicPlayer()).orElse(null);
                if (linkedUser != null) displayName = linkedUser.getUsername();
            }
            String identity = linkedUser != null ? "u:" + linkedUser.getId() : "n:" + displayName.toLowerCase(Locale.ROOT);
            if (!identities.add(identity)) throw new IllegalArgumentException("Один и тот же игрок не может занимать два места в одной игре.");
            GameRole role = parseRole(input.role());
            BigDecimal bonus = normalizePoints(input.parsedBonusPoints());
            BigDecimal penalty = normalizePoints(input.parsedPenaltyPoints());
            Integer move1 = parseOptionalSeat(input.bestMove1());
            Integer move2 = parseOptionalSeat(input.bestMove2());
            Integer move3 = parseOptionalSeat(input.bestMove3());
            boolean hasAnyMove = move1 != null || move2 != null || move3 != null;
            if (input.firstKilled()) firstKilledCount++;
            if (firstKilledCount > 1) throw new IllegalArgumentException("В одной игре можно отметить только одного первого убитого игрока.");
            if ((move2 != null && move1 == null) || (move3 != null && move2 == null)) throw new IllegalArgumentException("Номера лучшего хода заполняются слева направо без пропусков.");
            if (hasAnyMove && !input.firstKilled()) throw new IllegalArgumentException("Лучший ход можно указать только для первого убитого игрока.");
            GamePlayer player = new GamePlayer();
            player.setSeatNumber(input.seatNumber()); player.setUser(linkedUser); player.setDisplayName(displayName); player.setRole(role); player.setFirstKilled(input.firstKilled());
            player.setBestMove1(move1); player.setBestMove2(move2); player.setBestMove3(move3); player.setBonusPoints(bonus); player.setPenaltyPoints(penalty); result.add(player);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы одного игрока.");
        if (result.size() > 10) throw new IllegalArgumentException("В одной игре может быть не более 10 игроков.");
        Set<Integer> existingSeats = new HashSet<>(); result.forEach(player -> existingSeats.add(player.getSeatNumber()));
        for (GamePlayer player : result) {
            if ((player.getBestMove1() != null && !existingSeats.contains(player.getBestMove1())) || (player.getBestMove2() != null && !existingSeats.contains(player.getBestMove2())) || (player.getBestMove3() != null && !existingSeats.contains(player.getBestMove3()))) throw new IllegalArgumentException("Лучший ход может ссылаться только на номера игроков этой игры.");
        }
        result.sort(Comparator.comparingInt(GamePlayer::getSeatNumber));
        return result;
    }

    static void calculatePoints(List<GamePlayer> players, GameWinner winner) {
        Map<Integer, GamePlayer> bySeat = new HashMap<>(); players.forEach(player -> bySeat.put(player.getSeatNumber(), player));
        for (GamePlayer player : players) {
            boolean black = player.getRole().isBlackTeam();
            boolean won = winner == GameWinner.BLACKS && black || winner == GameWinner.CIVILIANS && !black;
            player.setWon(won); player.setVictoryPoints(won ? WIN_POINTS : BigDecimal.ZERO);
            BigDecimal firstKilled = BigDecimal.ZERO; BigDecimal bestMove = BigDecimal.ZERO;
            if (player.isFirstKilled() && !black) {
                firstKilled = FIRST_KILLED_POINTS;
                if (player.getBestMove1() != null) {
                    Set<Integer> unique = new HashSet<>();
                    if (player.getBestMove1() != null) unique.add(player.getBestMove1());
                    if (player.getBestMove2() != null) unique.add(player.getBestMove2());
                    if (player.getBestMove3() != null) unique.add(player.getBestMove3());
                    int blackHits = 0;
                    for (Integer seat : unique) if (bySeat.get(seat).getRole().isBlackTeam()) blackHits++;
                    if (blackHits == 2) bestMove = BEST_MOVE_TWO_POINTS; else if (blackHits == 3) bestMove = BEST_MOVE_THREE_POINTS;
                }
            }
            player.setFirstKilledPoints(firstKilled); player.setBestMovePoints(bestMove);
            player.setTotalPoints(player.getVictoryPoints().add(firstKilled).add(bestMove).add(player.getBonusPoints()).subtract(player.getPenaltyPoints()).setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void recalculateRegisteredPlayerStats(Set<Long> affectedPlayerIds) {
        if (affectedPlayerIds.isEmpty()) return;

        Map<Long, MutableStats> stats = new HashMap<>();
        affectedPlayerIds.forEach(id -> stats.put(id, new MutableStats()));
        for (PlayerStatsRowProjection row : gamePlayers.findStatsRowsByUserIds(affectedPlayerIds)) {
            MutableStats item = stats.computeIfAbsent(row.userId(), ignored -> new MutableStats());
            item.games++;
            if (row.won()) item.wins++;
            else if (row.winner() != GameWinner.DRAW) item.losses++;
            item.additionalPoints = item.additionalPoints.add(row.additionalPoints());
        }

        List<Role> publicRoles = List.of(Role.PLAYER, Role.MODERATOR);
        for (Long userId : affectedPlayerIds) {
            MutableStats item = stats.get(userId);
            double average = item.games == 0
                ? 0.0
                : item.additionalPoints.divide(BigDecimal.valueOf(item.games), 4, RoundingMode.HALF_UP).doubleValue();
            users.updatePublicPlayerStats(userId, publicRoles, item.games, item.wins, item.losses, average);
        }
    }

    private List<String> parseVideoLinks(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = text == null ? "" : text.replace(',', '\n');
        for (String raw : normalized.split("\\R")) {
            String value = raw.strip(); if (value.isEmpty()) continue;
            if (value.length() > 500) throw new IllegalArgumentException("Ссылка на видеозапись слишком длинная.");
            try {
                URI uri = URI.create(value);
                String scheme = uri.getScheme();
                if (scheme == null || !scheme.equalsIgnoreCase("https") || uri.getHost() == null) throw new IllegalArgumentException("Ссылка на видеозапись должна использовать HTTPS: " + value);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Некорректная ссылка на видеозапись: " + value);
            }
            result.add(value);
            if (result.size() > 10) throw new IllegalArgumentException("К одной игре можно добавить не более 10 видеоссылок.");
        }
        return new ArrayList<>(result);
    }

    private List<User> parseVisibilityUsers(String text) {
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        List<User> result = new ArrayList<>();
        String normalized = text == null ? "" : text.replace(',', '\n').replace(';', '\n');
        for (String raw : normalized.split("\\R")) {
            String value = raw.strip(); if (value.isEmpty()) continue;
            User user = users.findByUsernameKey(User.canonicalUsername(value)).filter(item -> item.getRole().isPublicPlayer()).orElseThrow(() -> new IllegalArgumentException("Игрок для доступа не найден: " + value));
            if (seen.add(user.getId())) result.add(user);
            if (result.size() > 100) throw new IllegalArgumentException("Для одной скрытой игры можно выбрать не более 100 игроков.");
        }
        return result;
    }

    private GameRole parseRole(String value) { if (value == null || value.isBlank()) return GameRole.CIVILIAN; try { return GameRole.valueOf(value); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректная роль игрока."); } }
    private Integer parseOptionalSeat(String value) { if (value == null || value.isBlank()) return null; long parsed = parseLong(value, "Некорректный номер в лучшем ходе."); if (parsed < 1 || parsed > 10) throw new IllegalArgumentException("Номера в лучшем ходе должны быть от 1 до 10."); return (int) parsed; }
    private long parseLong(String value, String message) { try { return Long.parseLong(value.trim()); } catch (RuntimeException ex) { throw new IllegalArgumentException(message); } }
    private BigDecimal normalizePoints(BigDecimal value) { if (value == null || value.signum() < 0) throw new IllegalArgumentException("Дополнительные и штрафные баллы не могут быть отрицательными."); if (value.compareTo(new BigDecimal("999999.99")) > 0) throw new IllegalArgumentException("Слишком большое значение баллов."); return value.setScale(2, RoundingMode.HALF_UP); }
    private String normalize(String value) { return value == null ? "" : value.strip(); }

    public record GameDetailRead(
        GameDetailProjection game,
        List<GameDetailPlayerProjection> players,
        List<String> videoLinks,
        List<String> visibilityUsernames
    ) { }

    private static class MutableRanking { private final String name; private final Long userId; private int games; private int wins; private BigDecimal totalPoints = BigDecimal.ZERO; private BigDecimal additionalPoints = BigDecimal.ZERO; private MutableRanking(String name, Long userId) { this.name = name; this.userId = userId; } }
    private static class MutableStats { private int games; private int wins; private int losses; private BigDecimal additionalPoints = BigDecimal.ZERO; }
}
