package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.tournaments.TournamentActionResponse;
import com.mafia.classicmafia.api.dto.tournaments.TournamentDetailResponse;
import com.mafia.classicmafia.api.dto.tournaments.TournamentGameResponse;
import com.mafia.classicmafia.api.dto.tournaments.TournamentInviteRequest;
import com.mafia.classicmafia.api.dto.tournaments.TournamentListItemResponse;
import com.mafia.classicmafia.api.dto.tournaments.TournamentParticipantResponse;
import com.mafia.classicmafia.api.dto.tournaments.TournamentWriteRequest;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.Tournament;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import com.mafia.classicmafia.repository.projection.TournamentParticipantReadProjection;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import com.mafia.classicmafia.service.TournamentService;
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

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tournaments")
public class TournamentsApiController {
    private final TournamentService tournaments;
    private final ModeratorPermissionService permissions;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;

    public TournamentsApiController(
        TournamentService tournaments,
        ModeratorPermissionService permissions,
        CurrentUserService currentUserService,
        AuditLogService auditLogService
    ) {
        this.tournaments = tournaments;
        this.permissions = permissions;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    List<TournamentListItemResponse> list(@RequestParam(defaultValue = "upcoming") String view) {
        String normalized = view == null ? "upcoming" : view.strip().toLowerCase();
        if (!normalized.equals("upcoming") && !normalized.equals("completed")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIEW_INVALID", "Допустимый вид турниров: upcoming или completed.");
        }
        boolean completed = normalized.equals("completed");
        return tournaments.list(completed).stream().map(this::toListItem).toList();
    }

    @GetMapping("/{id}")
    ApiDataResponse<TournamentDetailResponse> detail(@PathVariable Long id, Authentication authentication) {
        Tournament tournament = require(id);
        SecurityAccountProjection viewer = viewerSecurity(authentication);
        Long viewerId = viewer == null ? null : viewer.id();
        boolean admin = viewer != null && viewer.role() == Role.ADMIN;
        boolean manageable = viewer != null && permissions.isAllowed(viewer.id(), viewer.role(), ModeratorScope.GAMES_MANAGE);
        var viewerState = tournaments.viewerState(id, viewerId);
        List<TournamentParticipantResponse> invitationRows = manageable
            ? tournaments.participantRows(id).stream().filter(TournamentParticipantReadProjection::invited).map(this::participant).toList()
            : List.of();
        List<TournamentGameResponse> games = tournaments.visibleGames(tournament, viewerId, admin).stream()
            .map(game -> new TournamentGameResponse(game.id(), game.name(), game.gameDate(), game.host(), game.hiddenFromPublic()))
            .toList();
        return new ApiDataResponse<>(new TournamentDetailResponse(
            tournament.getId(), tournament.getName(), dates(tournament), tournament.getGameCount(), tournament.isPaid(), tournament.getParticipationCost(),
            tournament.getDescription(), tournament.isCompleted(), tournaments.participantCount(id), viewerState.participating(),
            viewerState.invited(), manageable, invitationRows, games
        ));
    }

    @PostMapping
    ApiDataResponse<TournamentActionResponse> create(@RequestBody TournamentWriteRequest body, Authentication authentication) {
        requireManage(authentication);
        Tournament saved = save(null, body);
        auditLogService.success("TOURNAMENT_CREATE", currentUserService.require(authentication), "tournament:" + saved.getId(), "saved;api");
        return new ApiDataResponse<>(new TournamentActionResponse(saved.getId(), false, "Турнир сохранён."));
    }

    @PutMapping("/{id}")
    ApiDataResponse<TournamentActionResponse> update(@PathVariable Long id, @RequestBody TournamentWriteRequest body, Authentication authentication) {
        requireManage(authentication);
        require(id);
        Tournament saved = save(id, body);
        auditLogService.success("TOURNAMENT_UPDATE", currentUserService.require(authentication), "tournament:" + saved.getId(), "saved;api");
        return new ApiDataResponse<>(new TournamentActionResponse(saved.getId(), false, "Турнир обновлён."));
    }

    @DeleteMapping("/{id}")
    ApiDataResponse<TournamentActionResponse> delete(@PathVariable Long id, Authentication authentication) {
        requireManage(authentication);
        try { tournaments.delete(id); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_DELETE_BLOCKED", "Турнир нельзя удалить в текущем состоянии."); }
        auditLogService.success("TOURNAMENT_DELETE", currentUserService.require(authentication), "tournament:" + id, "deleted;api");
        return new ApiDataResponse<>(new TournamentActionResponse(id, false, "Турнир удалён."));
    }

    @PostMapping("/{id}/participation")
    ApiDataResponse<TournamentActionResponse> participation(@PathVariable Long id, Authentication authentication) {
        User user = currentUserService.require(authentication);
        try {
            boolean joined = tournaments.toggleParticipation(id, user);
            return new ApiDataResponse<>(new TournamentActionResponse(id, joined, joined ? "Вы участвуете в турнире." : "Участие отменено."));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_PARTICIPATION_INVALID", "Участие в турнире сейчас изменить нельзя.");
        }
    }

    @PostMapping("/{id}/invitations")
    ApiDataResponse<TournamentActionResponse> invite(@PathVariable Long id, @RequestBody TournamentInviteRequest body, Authentication authentication) {
        requireManage(authentication);
        try {
            User user = tournaments.invite(id, body == null ? "" : body.username());
            auditLogService.success("TOURNAMENT_INVITE", currentUserService.require(authentication), "tournament:" + id, "user:" + user.getId() + ";api");
            return new ApiDataResponse<>(new TournamentActionResponse(id, false, "Приглашение выдано игроку " + user.getUsername() + "."));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_INVITE_INVALID", "Приглашение не может быть изменено с этими данными.");
        }
    }

    @DeleteMapping("/{id}/invitations/{userId}")
    ApiDataResponse<TournamentActionResponse> removeInvite(@PathVariable Long id, @PathVariable Long userId, Authentication authentication) {
        requireManage(authentication);
        require(id);
        tournaments.removeInvitation(id, userId);
        auditLogService.success("TOURNAMENT_INVITE_REMOVE", currentUserService.require(authentication), "tournament:" + id, "user:" + userId + ";api");
        return new ApiDataResponse<>(new TournamentActionResponse(id, false, "Приглашение снято."));
    }

    private Tournament save(Long id, TournamentWriteRequest body) {
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_INVALID", "Данные турнира обязательны.");
        String dateText = body.dates() == null ? "" : body.dates().stream().map(Object::toString).collect(Collectors.joining("\n"));
        try { return tournaments.save(id, body.name(), dateText, body.gameCount(), body.participationCost(), body.description()); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "TOURNAMENT_INVALID", "Проверьте данные турнира."); }
    }

    private Tournament require(Long id) {
        try { return tournaments.require(id); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "TOURNAMENT_NOT_FOUND", "Турнир не найден."); }
    }

    private TournamentListItemResponse toListItem(Tournament tournament) {
        return new TournamentListItemResponse(tournament.getId(), tournament.getName(), dates(tournament), tournament.getGameCount(), tournament.isPaid(), tournament.getParticipationCost(), tournament.isCompleted());
    }

    private TournamentParticipantResponse participant(TournamentParticipantReadProjection row) {
        return new TournamentParticipantResponse(row.userId(), row.username(), row.invited(), row.participating());
    }

    private List<java.time.LocalDate> dates(Tournament tournament) {
        return tournament.getDates().stream().map(item -> item.getEventDate()).toList();
    }

    private void requireManage(Authentication authentication) {
        try { permissions.require(authentication, ModeratorScope.GAMES_MANAGE); }
        catch (RuntimeException exception) { throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав для управления турнирами."); }
    }

    private SecurityAccountProjection viewerSecurity(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try { return currentUserService.requireSecurity(authentication); }
        catch (RuntimeException exception) { return null; }
    }
}
