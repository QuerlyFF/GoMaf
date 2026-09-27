package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.PlayerAdministrationActionResponse;
import com.mafia.classicmafia.api.dto.PlayerAdministrationCapabilitiesResponse;
import com.mafia.classicmafia.api.dto.PlayerAvatarResetRequest;
import com.mafia.classicmafia.api.dto.PlayerDeleteRequest;
import com.mafia.classicmafia.api.dto.PlayerPasswordResetRequest;
import com.mafia.classicmafia.api.dto.PlayerProfileUpdateRequest;
import com.mafia.classicmafia.api.dto.PlayerRatingUpdateRequest;
import com.mafia.classicmafia.api.dto.PlayerUsernameUpdateRequest;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import com.mafia.classicmafia.service.PlayerAdministrationService;
import com.mafia.classicmafia.service.PlayerAdministrationService.PlayerAdministrationException;
import com.mafia.classicmafia.service.SensitiveActionService;
import com.mafia.classicmafia.service.SensitiveActionService.SensitiveActionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/players")
public class PlayerAdministrationApiController {
    private static final Logger log = LoggerFactory.getLogger(PlayerAdministrationApiController.class);

    private final PlayerAdministrationService administration;
    private final CurrentUserService currentUsers;
    private final ModeratorPermissionService permissions;
    private final SensitiveActionService sensitiveActions;
    private final AuditLogService audit;

    public PlayerAdministrationApiController(
        PlayerAdministrationService administration,
        CurrentUserService currentUsers,
        ModeratorPermissionService permissions,
        SensitiveActionService sensitiveActions,
        AuditLogService audit
    ) {
        this.administration = administration;
        this.currentUsers = currentUsers;
        this.permissions = permissions;
        this.sensitiveActions = sensitiveActions;
        this.audit = audit;
    }

    @GetMapping("/{id}/administration")
    ApiDataResponse<PlayerAdministrationCapabilitiesResponse> capabilities(
        @PathVariable Long id,
        Authentication authentication
    ) {
        User operator = currentUsers.require(authentication);
        User target = map(() -> administration.requirePublicTarget(id), operator, "PLAYER_ADMIN_CAPABILITIES", id);
        boolean manageable = canManageTarget(operator, target);
        boolean admin = operator.getRole() == Role.ADMIN;
        return new ApiDataResponse<>(new PlayerAdministrationCapabilitiesResponse(
            manageable,
            manageable && permissions.isAllowed(operator, ModeratorScope.PLAYER_RATING_MANAGE),
            manageable && permissions.isAllowed(operator, ModeratorScope.PLAYER_USERNAME_MANAGE),
            manageable && permissions.isAllowed(operator, ModeratorScope.PLAYER_PASSWORD_RESET),
            manageable && permissions.isAllowed(operator, ModeratorScope.PLAYER_AVATAR_RESET),
            admin,
            manageable && permissions.isAllowed(operator, ModeratorScope.PLAYER_DELETE),
            true
        ));
    }

    @PatchMapping("/{id}/rating")
    ApiDataResponse<PlayerAdministrationActionResponse> rating(
        @PathVariable Long id,
        @RequestBody PlayerRatingUpdateRequest body,
        Authentication authentication
    ) {
        User operator = requireScope(authentication, ModeratorScope.PLAYER_RATING_MANAGE, "PLAYER_RATING_UPDATE", id);
        if (body == null || body.rating() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RATING_REQUIRED", "Укажите рейтинг.");
        }
        User player = map(() -> administration.updateRating(operator, id, body.rating()), operator, "PLAYER_RATING_UPDATE", id);
        audit.success("PLAYER_RATING_UPDATE", operator, "user:" + player.getId(), "rating-updated");
        return action(player.getId(), player.getUsername(), "Рейтинг игрока сохранён.");
    }

    @PatchMapping("/{id}/username")
    ApiDataResponse<PlayerAdministrationActionResponse> username(
        @PathVariable Long id,
        @RequestBody PlayerUsernameUpdateRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User operator = requireScope(authentication, ModeratorScope.PLAYER_USERNAME_MANAGE, "PLAYER_USERNAME_UPDATE", id);
        requireReauthentication(operator, authentication, body == null ? null : body.currentPassword(), request, "PLAYER_USERNAME_UPDATE", id);
        var result = map(
            () -> administration.rename(operator, id, body == null ? null : body.username()),
            operator,
            "PLAYER_USERNAME_UPDATE",
            id
        );
        if (result.changed()) {
            log.info("{} {} переименовал игрока {} в {} через API.", operator.getRole(), operator.getUsername(), result.oldUsername(), result.newUsername());
            audit.success("PLAYER_USERNAME_UPDATE", operator, "user:" + id, "username-changed;session-invalidated");
            return action(id, result.newUsername(), "Ник игрока изменён. Активные сессии игрока завершены.");
        }
        return action(id, result.newUsername(), "Ник игрока не изменён.");
    }

    @PutMapping("/{id}/password")
    ApiDataResponse<PlayerAdministrationActionResponse> password(
        @PathVariable Long id,
        @RequestBody PlayerPasswordResetRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User operator = requireScope(authentication, ModeratorScope.PLAYER_PASSWORD_RESET, "PLAYER_PASSWORD_RESET", id);
        requireReauthentication(operator, authentication, body == null ? null : body.currentPassword(), request, "PLAYER_PASSWORD_RESET", id);
        var result = map(
            () -> administration.resetPassword(operator, id, body == null ? null : body.password()),
            operator,
            "PLAYER_PASSWORD_RESET",
            id
        );
        log.info("{} {} изменил пароль игрока {} через API.", operator.getRole(), operator.getUsername(), result.username());
        audit.success("PLAYER_PASSWORD_RESET", operator, "user:" + id, "session-invalidated");
        return action(id, result.username(), "Пароль игрока изменён. Активные сессии игрока завершены.");
    }

    @DeleteMapping("/{id}/avatar")
    ApiDataResponse<PlayerAdministrationActionResponse> avatar(
        @PathVariable Long id,
        @RequestBody(required = false) PlayerAvatarResetRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User operator = requireScope(authentication, ModeratorScope.PLAYER_AVATAR_RESET, "PLAYER_AVATAR_RESET", id);
        requireReauthentication(operator, authentication, body == null ? null : body.currentPassword(), request, "PLAYER_AVATAR_RESET", id);
        String username = map(() -> administration.resetAvatar(operator, id), operator, "PLAYER_AVATAR_RESET", id);
        log.info("{} {} сбросил аватар игрока {} через API.", operator.getRole(), operator.getUsername(), username);
        audit.success("PLAYER_AVATAR_RESET", operator, "user:" + id, "avatar-cleared");
        return action(id, username, "Аватар игрока сброшен.");
    }

    @PatchMapping("/{id}/profile")
    ApiDataResponse<PlayerAdministrationActionResponse> profile(
        @PathVariable Long id,
        @RequestBody PlayerProfileUpdateRequest body,
        Authentication authentication
    ) {
        User operator = currentUsers.require(authentication);
        if (operator.getRole() != Role.ADMIN) {
            audit.denied("PLAYER_ABOUT_UPDATE", operator, "user:" + id, "admin-only");
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAYER_ADMIN_FORBIDDEN", "Недостаточно прав.");
        }
        User player = map(
            () -> administration.updateAboutAsAdmin(operator, id, body == null ? null : body.aboutMe()),
            operator,
            "PLAYER_ABOUT_UPDATE",
            id
        );
        audit.success("PLAYER_ABOUT_UPDATE", operator, "user:" + id, "updated");
        return action(id, player.getUsername(), "Описание профиля игрока сохранено.");
    }

    @DeleteMapping("/{id}")
    ApiDataResponse<PlayerAdministrationActionResponse> delete(
        @PathVariable Long id,
        @RequestBody PlayerDeleteRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User operator = requireScope(authentication, ModeratorScope.PLAYER_DELETE, "PLAYER_DELETE", id);
        requireReauthentication(operator, authentication, body == null ? null : body.currentPassword(), request, "PLAYER_DELETE", id);
        var result = map(
            () -> administration.delete(operator, id, body == null ? null : body.confirmation()),
            operator,
            "PLAYER_DELETE",
            id
        );
        log.info("{} {} удалил аккаунт игрока {} через API. Отвязано записей истории: {}.", operator.getRole(), operator.getUsername(), result.username(), result.detachedHistoryRows());
        audit.success("PLAYER_DELETE", operator, "user:" + id, "history-detached:" + result.detachedHistoryRows());
        return action(id, result.username(), "Аккаунт игрока удалён. История игр сохранена.");
    }

    private User requireScope(
        Authentication authentication,
        ModeratorScope scope,
        String action,
        Long targetId
    ) {
        User operator = currentUsers.require(authentication);
        if (!permissions.isAllowed(operator, scope)) {
            audit.denied(action, operator, "user:" + targetId, "scope-denied:" + scope.name());
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAYER_ADMIN_FORBIDDEN", "Недостаточно прав.");
        }
        return operator;
    }

    private void requireReauthentication(
        User operator,
        Authentication authentication,
        String currentPassword,
        HttpServletRequest request,
        String action,
        Long targetId
    ) {
        try {
            sensitiveActions.verify(authentication, currentPassword, request);
        } catch (SensitiveActionException exception) {
            audit.denied(action, operator, "user:" + targetId, "reauth-failed");
            if (exception.reason() == SensitiveActionService.Reason.RATE_LIMITED) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "REAUTH_RATE_LIMITED", "Слишком много попыток подтверждения. Попробуйте позже.");
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "REAUTH_FAILED", "Не удалось подтвердить действие.");
        }
    }

    private boolean canManageTarget(User operator, User target) {
        if (operator.getRole() == Role.ADMIN) {
            return true;
        }
        return operator.getRole() == Role.MODERATOR
            && target.getRole() == Role.PLAYER
            && !operator.getId().equals(target.getId());
    }

    private <T> T map(AdminCall<T> call, User operator, String action, Long targetId) {
        try {
            return call.call();
        } catch (PlayerAdministrationException exception) {
            HttpStatus status = switch (exception.reason()) {
                case VALIDATION -> HttpStatus.BAD_REQUEST;
                case CONFLICT -> HttpStatus.CONFLICT;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
            };
            String code = switch (exception.reason()) {
                case VALIDATION -> "PLAYER_ADMIN_INVALID";
                case CONFLICT -> "PLAYER_ADMIN_CONFLICT";
                case FORBIDDEN -> "PLAYER_ADMIN_FORBIDDEN";
                case NOT_FOUND -> "PLAYER_NOT_FOUND";
            };
            if (exception.reason() == PlayerAdministrationService.Reason.FORBIDDEN) {
                audit.denied(action, operator, "user:" + targetId, "target-denied");
            }
            throw new ApiException(status, code, "Операция с аккаунтом не выполнена.");
        }
    }

    private ApiDataResponse<PlayerAdministrationActionResponse> action(Long id, String username, String message) {
        return new ApiDataResponse<>(new PlayerAdministrationActionResponse(id, username, message));
    }

    @FunctionalInterface
    private interface AdminCall<T> {
        T call();
    }
}
