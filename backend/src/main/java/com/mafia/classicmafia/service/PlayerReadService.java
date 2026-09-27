package com.mafia.classicmafia.service;

import com.mafia.classicmafia.api.ApiException;
import com.mafia.classicmafia.api.dto.ApiPageResponse;
import com.mafia.classicmafia.api.dto.PlayerGameResponse;
import com.mafia.classicmafia.api.dto.PlayerListItemResponse;
import com.mafia.classicmafia.api.dto.PlayerProfileResponse;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.GamePlayerRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.AvatarMediaProjection;
import com.mafia.classicmafia.repository.projection.BannerMediaProjection;
import com.mafia.classicmafia.repository.projection.PlayerGameProjection;
import com.mafia.classicmafia.repository.projection.PlayerListProjection;
import com.mafia.classicmafia.repository.projection.PlayerProfileProjection;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlayerReadService {
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_HISTORY_SIZE = 20;
    private static final int MAX_HISTORY_SIZE = 100;
    private static final List<Role> PUBLIC_ROLES = List.of(Role.PLAYER, Role.MODERATOR);

    private final UserRepository users;
    private final GamePlayerRepository gamePlayers;
    private final CurrentUserService currentUserService;

    public PlayerReadService(
        UserRepository users,
        GamePlayerRepository gamePlayers,
        CurrentUserService currentUserService
    ) {
        this.users = users;
        this.gamePlayers = gamePlayers;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public ApiPageResponse<PlayerListItemResponse> listPlayers(
        String rawQuery,
        int page,
        Integer size,
        String sort
    ) {
        String query = normalizeQuery(rawQuery);
        int safePage = requirePage(page);
        int safeSize = requireSize(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        Sort ordering = parseSort(sort);
        var pageable = PageRequest.of(safePage, safeSize, ordering);
        var result = query.isBlank()
            ? users.findPublicPlayerList(PUBLIC_ROLES, pageable)
            : users.searchPublicPlayerList(PUBLIC_ROLES, query, pageable);
        return ApiPageResponse.from(result, this::toListItem);
    }

    @Transactional(readOnly = true)
    public PlayerProfileResponse getProfile(Long id) {
        if (id == null) {
            throw notFound();
        }
        return users.findPublicPlayerProfile(id, PUBLIC_ROLES)
            .map(this::toProfile)
            .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public ApiPageResponse<PlayerGameResponse> getGames(
        Long id,
        Authentication authentication,
        int page,
        Integer size
    ) {
        requirePublicPlayerExists(id);
        return pageHistory(id, resolveViewer(authentication), page, size);
    }

    @Transactional(readOnly = true)
    public ApiPageResponse<PlayerGameResponse> getSelfGames(
        Authentication authentication,
        int page,
        Integer size
    ) {
        SecurityAccountProjection player = currentUserService.requireSecurity(authentication);
        return pageHistory(player.id(), player, page, size);
    }

    private ApiPageResponse<PlayerGameResponse> pageHistory(
        Long playerId,
        SecurityAccountProjection viewer,
        int page,
        Integer size
    ) {
        int safePage = requirePage(page);
        int safeSize = requireSize(size, DEFAULT_HISTORY_SIZE, MAX_HISTORY_SIZE);
        Long viewerId = viewer == null ? null : viewer.id();
        boolean viewerIsAdmin = viewer != null && viewer.role() == Role.ADMIN;
        var result = gamePlayers.findVisibleHistoryByUserId(
            playerId,
            viewerId,
            viewerIsAdmin,
            PageRequest.of(safePage, safeSize)
        );
        return ApiPageResponse.from(result, this::toGameResponse);
    }

    private void requirePublicPlayerExists(Long id) {
        if (id == null || users.findPublicPlayerProfile(id, PUBLIC_ROLES).isEmpty()) {
            throw notFound();
        }
    }

    @Transactional(readOnly = true)
    public AvatarMediaProjection requirePublicAvatarMedia(Long id) {
        if (id == null) {
            throw notFound();
        }
        return users.findPublicAvatarMedia(id, PUBLIC_ROLES).orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public BannerMediaProjection requirePublicBannerMedia(Long id) {
        if (id == null) {
            throw notFound();
        }
        return users.findPublicBannerMedia(id, PUBLIC_ROLES).orElseThrow(this::notFound);
    }

    private PlayerListItemResponse toListItem(PlayerListProjection user) {
        return new PlayerListItemResponse(
            user.id(),
            user.username(),
            user.gamesPlayed(),
            user.wins(),
            user.losses(),
            winRate(user.gamesPlayed(), user.wins()),
            user.averageScore(),
            user.rating(),
            avatarListUrl(user.id())
        );
    }

    private PlayerProfileResponse toProfile(PlayerProfileProjection user) {
        return new PlayerProfileResponse(
            user.id(),
            user.username(),
            user.aboutMe(),
            user.gamesPlayed(),
            user.wins(),
            user.losses(),
            winRate(user.gamesPlayed(), user.wins()),
            user.averageScore(),
            user.rating(),
            avatarUrl(user.id()),
            bannerUrl(user.id()),
            "/api/v1/players/" + user.id() + "/games"
        );
    }

    private double winRate(int gamesPlayed, int wins) {
        return gamesPlayed == 0 ? 0.0 : (double) wins / gamesPlayed;
    }

    private String avatarListUrl(Long id) {
        return avatarUrl(id) + "?size=128";
    }

    private String avatarUrl(Long id) {
        return "/api/v1/players/" + id + "/avatar";
    }

    private String bannerUrl(Long id) {
        return "/api/v1/players/" + id + "/banner";
    }

    private PlayerGameResponse toGameResponse(PlayerGameProjection row) {
        return new PlayerGameResponse(
            row.gameId(),
            row.name(),
            row.gameDate(),
            row.role().name(),
            row.role().getDisplayName(),
            row.totalPoints(),
            row.additionalPoints(),
            row.won()
        );
    }

    private SecurityAccountProjection resolveViewer(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return currentUserService.requireSecurity(authentication);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeQuery(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "Поисковый запрос не может быть длиннее 64 символов.");
        }
        return value;
    }

    private int requirePage(int page) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "Номер страницы не может быть отрицательным.");
        }
        return page;
    }

    private int requireSize(Integer size, int defaultSize, int maxSize) {
        int value = size == null ? defaultSize : size;
        if (value < 1 || value > maxSize) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_SIZE_INVALID", "Размер страницы должен быть от 1 до " + maxSize + ".");
        }
        return value;
    }

    private Sort parseSort(String rawSort) {
        String value = rawSort == null || rawSort.isBlank() ? "rating,desc" : rawSort.strip().toLowerCase();
        return switch (value) {
            case "rating,desc" -> Sort.by(Sort.Order.desc("rating"), Sort.Order.asc("username"));
            case "rating,asc" -> Sort.by(Sort.Order.asc("rating"), Sort.Order.asc("username"));
            case "username,asc" -> Sort.by(Sort.Order.asc("username"));
            case "username,desc" -> Sort.by(Sort.Order.desc("username"));
            case "gamesplayed,desc" -> Sort.by(Sort.Order.desc("gamesPlayed"), Sort.Order.asc("username"));
            case "gamesplayed,asc" -> Sort.by(Sort.Order.asc("gamesPlayed"), Sort.Order.asc("username"));
            case "wins,desc" -> Sort.by(Sort.Order.desc("wins"), Sort.Order.asc("username"));
            case "wins,asc" -> Sort.by(Sort.Order.asc("wins"), Sort.Order.asc("username"));
            default -> throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "SORT_INVALID",
                "Недопустимая сортировка игроков."
            );
        };
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Игрок не найден.");
    }
}
