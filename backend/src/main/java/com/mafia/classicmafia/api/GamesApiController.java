package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.ApiPageResponse;
import com.mafia.classicmafia.api.dto.games.GameActionResponse;
import com.mafia.classicmafia.api.dto.games.GameDetailResponse;
import com.mafia.classicmafia.api.dto.games.GameListItemResponse;
import com.mafia.classicmafia.api.dto.games.GamePlayerResponse;
import com.mafia.classicmafia.api.dto.games.GamePlayerWriteRequest;
import com.mafia.classicmafia.api.dto.games.GameWriteRequest;
import com.mafia.classicmafia.entity.Game;
import com.mafia.classicmafia.repository.projection.GameDetailPlayerProjection;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import com.mafia.classicmafia.entity.GameWinner;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Tournament;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.GameService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import com.mafia.classicmafia.service.TournamentService;
import com.mafia.classicmafia.view.GamePlayerInput;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/games")
public class GamesApiController {
    private final GameService games;
    private final TournamentService tournaments;
    private final ModeratorPermissionService permissions;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;

    public GamesApiController(
        GameService games,
        TournamentService tournaments,
        ModeratorPermissionService permissions,
        CurrentUserService currentUserService,
        AuditLogService auditLogService
    ) {
        this.games = games;
        this.tournaments = tournaments;
        this.permissions = permissions;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    ApiPageResponse<GameListItemResponse> list(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "Номер страницы не может быть отрицательным.");
        if (size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_SIZE_INVALID", "Размер страницы должен быть от 1 до 100.");
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "Поисковый запрос не может быть длиннее 80 символов.");
        var result = games.searchGames(query, PageRequest.of(page, size, Sort.by(Sort.Order.desc("gameDate"), Sort.Order.desc("id"))));
        return ApiPageResponse.from(result, this::toListItem);
    }

    @GetMapping("/{id}")
    ApiDataResponse<GameDetailResponse> detail(@PathVariable Long id, Authentication authentication) {
        try {
            boolean manageable = permissions.isAllowed(authentication, ModeratorScope.GAMES_MANAGE);
            var game = games.getDetailRead(id, viewerSecurity(authentication), manageable);
            return new ApiDataResponse<>(toDetail(game, manageable));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Игра не найдена или недоступна.");
        }
    }

    @PostMapping
    ApiDataResponse<GameActionResponse> create(@RequestBody GameWriteRequest body, Authentication authentication) {
        requireManage(authentication);
        Tournament tournament = tournament(body == null ? null : body.tournamentId());
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_INVALID", "Данные игры обязательны.");
        Game saved = save(null, tournament, body, authentication);
        return new ApiDataResponse<>(new GameActionResponse(saved.getId(), "Игра сохранена, баллы рассчитаны автоматически."));
    }

    @PutMapping("/{id}")
    ApiDataResponse<GameActionResponse> update(@PathVariable Long id, @RequestBody GameWriteRequest body, Authentication authentication) {
        requireManage(authentication);
        Game existing;
        try { existing = games.getDetailed(id); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Игра не найдена."); }
        Long requestedTournamentId = body == null ? null : body.tournamentId();
        Tournament tournament = requestedTournamentId == null ? existing.getTournament() : tournament(requestedTournamentId);
        if (existing.getTournament() != null && tournament != null && !existing.getTournament().getId().equals(tournament.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_MISMATCH", "Игра принадлежит другому турниру.");
        }
        Game saved = save(id, tournament, body, authentication);
        return new ApiDataResponse<>(new GameActionResponse(saved.getId(), "Игра обновлена, баллы пересчитаны."));
    }

    @DeleteMapping("/{id}")
    ApiDataResponse<GameActionResponse> delete(@PathVariable Long id, Authentication authentication) {
        requireManage(authentication);
        try { games.getDetailed(id); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Игра не найдена."); }
        games.deleteGame(id);
        User actor = currentUserService.require(authentication);
        auditLogService.success("GAME_DELETE", actor, "game:" + id, "deleted;stats-recalculated;api");
        return new ApiDataResponse<>(new GameActionResponse(id, "Игра удалена, статистика игроков пересчитана."));
    }

    private Game save(Long id, Tournament tournament, GameWriteRequest body, Authentication authentication) {
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_INVALID", "Данные игры обязательны.");
        if (body.hiddenFromPublic() && tournament == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HIDDEN_GAME_REQUIRES_TOURNAMENT", "Скрывать можно только игру внутри турнира.");
        }
        GameWinner winner;
        try { winner = GameWinner.valueOf(body.winner() == null ? "" : body.winner()); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "WINNER_INVALID", "Укажите победившую сторону."); }
        List<GamePlayerInput> inputs = toInputs(body.players());
        String videoLinks = String.join("\n", body.videoLinks() == null ? List.of() : body.videoLinks());
        String visibility = String.join("\n", body.visibilityUsernames() == null ? List.of() : body.visibilityUsernames());
        try {
            Game saved = games.saveGame(id, body.name(), body.gameDate(), body.host(), winner, body.summary(), inputs, videoLinks, tournament, body.hiddenFromPublic(), visibility);
            User actor = currentUserService.require(authentication);
            auditLogService.success(id == null ? "GAME_CREATE" : "GAME_UPDATE", actor, "game:" + saved.getId(), "saved;stats-recalculated;api;tournament:" + (tournament == null ? "none" : tournament.getId()));
            return saved;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_INVALID", "Проверьте данные игры.");
        }
    }

    private List<GamePlayerInput> toInputs(List<GamePlayerWriteRequest> rows) {
        List<GamePlayerInput> result = new ArrayList<>();
        if (rows == null) return result;
        for (GamePlayerWriteRequest row : rows) {
            if (row == null) continue;
            result.add(new GamePlayerInput(
                row.seatNumber(),
                "",
                row.username() == null ? "" : row.username(),
                row.role() == null ? "" : row.role(),
                row.firstKilled(),
                value(row.bestMove1()),
                value(row.bestMove2()),
                value(row.bestMove3()),
                row.bonusPoints() == null ? "" : row.bonusPoints(),
                row.penaltyPoints() == null ? "" : row.penaltyPoints()
            ));
        }
        return result;
    }

    private GameListItemResponse toListItem(Game game) {
        Tournament tournament = game.getTournament();
        return new GameListItemResponse(
            game.getId(), game.getName(), game.getGameDate(), game.getHost(),
            game.getWinner().name(), game.getWinner().getDisplayName(),
            tournament == null ? null : tournament.getId(), tournament == null ? null : tournament.getName()
        );
    }

    private GameDetailResponse toDetail(GameService.GameDetailRead read, boolean manageable) {
        var game = read.game();
        List<GamePlayerResponse> players = read.players().stream().map(this::toPlayer).toList();
        return new GameDetailResponse(
            game.id(), game.name(), game.gameDate(), game.host(), game.winner().name(), game.winner().getDisplayName(),
            game.summary(), game.hiddenFromPublic(), game.tournamentId(), game.tournamentName(),
            players, read.videoLinks(), read.visibilityUsernames(), manageable
        );
    }

    private GamePlayerResponse toPlayer(GameDetailPlayerProjection row) {
        return new GamePlayerResponse(
            row.seatNumber(), row.publicUserId(), row.publicDisplayName(), row.role().name(), row.role().getDisplayName(), row.firstKilled(),
            row.bestMove1(), row.bestMove2(), row.bestMove3(), row.victoryPoints(), row.firstKilledPoints(), row.bestMovePoints(),
            row.bonusPoints(), row.penaltyPoints(), row.additionalPoints(), row.totalPoints(), row.won()
        );
    }

    private Tournament tournament(Long id) {
        if (id == null) return null;
        try { return tournaments.require(id); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_NOT_FOUND", "Турнир не найден."); }
    }

    private void requireManage(Authentication authentication) {
        try { permissions.require(authentication, ModeratorScope.GAMES_MANAGE); }
        catch (RuntimeException exception) { throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав для управления играми."); }
    }

    private SecurityAccountProjection viewerSecurity(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try { return currentUserService.requireSecurity(authentication); }
        catch (RuntimeException exception) { return null; }
    }

    private String value(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }
}
