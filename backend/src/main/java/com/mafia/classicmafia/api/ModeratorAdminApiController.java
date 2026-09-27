package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.ApiPageResponse;
import com.mafia.classicmafia.api.dto.admin.EligibleModeratorPlayerResponse;
import com.mafia.classicmafia.api.dto.admin.ModeratorActionResponse;
import com.mafia.classicmafia.api.dto.admin.ModeratorAssignmentRequest;
import com.mafia.classicmafia.api.dto.admin.ModeratorDefaultsResponse;
import com.mafia.classicmafia.api.dto.admin.ModeratorDefaultsUpdateRequest;
import com.mafia.classicmafia.api.dto.admin.ModeratorOverridesUpdateRequest;
import com.mafia.classicmafia.api.dto.admin.ModeratorResponse;
import com.mafia.classicmafia.api.dto.admin.ModeratorScopeCellResponse;
import com.mafia.classicmafia.api.dto.admin.ModeratorScopeResponse;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.AdminUserSummaryProjection;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.ModeratorAdministrationService;
import com.mafia.classicmafia.service.SensitiveActionService;
import com.mafia.classicmafia.service.SensitiveActionService.SensitiveActionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
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

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/moderators")
public class ModeratorAdminApiController {
    private static final Logger log = LoggerFactory.getLogger(ModeratorAdminApiController.class);

    private final UserRepository users;
    private final CurrentUserService currentUsers;
    private final SensitiveActionService sensitiveActions;
    private final ModeratorAdministrationService administration;
    private final AuditLogService audit;

    public ModeratorAdminApiController(
        UserRepository users,
        CurrentUserService currentUsers,
        SensitiveActionService sensitiveActions,
        ModeratorAdministrationService administration,
        AuditLogService audit
    ) {
        this.users = users;
        this.currentUsers = currentUsers;
        this.sensitiveActions = sensitiveActions;
        this.administration = administration;
        this.audit = audit;
    }

    @GetMapping("/defaults")
    ApiDataResponse<ModeratorDefaultsResponse> defaults(Authentication authentication) {
        requireCanonicalAdmin(authentication);
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        administration.defaultMap().forEach((scope, enabled) -> defaults.put(scope.name(), enabled));
        List<ModeratorScopeResponse> scopes = administration.scopes().stream()
            .map(scope -> new ModeratorScopeResponse(scope.name(), scope.getDisplayName()))
            .toList();
        return new ApiDataResponse<>(new ModeratorDefaultsResponse(scopes, defaults));
    }

    @GetMapping
    ApiPageResponse<ModeratorResponse> moderators(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Authentication authentication
    ) {
        requireCanonicalAdmin(authentication);
        String query = normalizeQuery(q);
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "Номер страницы не может быть отрицательным.");
        }
        if (size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_SIZE_INVALID", "Размер страницы должен быть от 1 до 100.");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("username")));
        Page<AdminUserSummaryProjection> result = query.isBlank()
            ? users.findAdminUserSummaries(List.of(Role.MODERATOR), pageable)
            : users.searchAdminUserSummaries(List.of(Role.MODERATOR), query, pageable);
        Map<ModeratorScope, Boolean> defaults = administration.defaultMap();
        return ApiPageResponse.from(result, moderator -> toModerator(moderator, defaults));
    }

    @GetMapping("/eligible")
    ApiPageResponse<EligibleModeratorPlayerResponse> eligible(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        Authentication authentication
    ) {
        requireCanonicalAdmin(authentication);
        String query = normalizeQuery(q);
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "Номер страницы не может быть отрицательным.");
        }
        if (size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_SIZE_INVALID", "Размер страницы должен быть от 1 до 100.");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("username")));
        Page<AdminUserSummaryProjection> result = query.isBlank()
            ? users.findAdminUserSummaries(List.of(Role.PLAYER), pageable)
            : users.searchAdminUserSummaries(List.of(Role.PLAYER), query, pageable);
        return ApiPageResponse.from(result, user -> new EligibleModeratorPlayerResponse(user.id(), user.username()));
    }

    @PutMapping("/defaults")
    ApiDataResponse<ModeratorActionResponse> updateDefaults(
        @RequestBody ModeratorDefaultsUpdateRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User admin = requireAdminAndPassword(authentication, body == null ? null : body.currentPassword(), request, "MODERATOR_SCOPE_DEFAULTS_BULK", "moderator-defaults");
        Map<ModeratorScope, Boolean> desired = parseDefaults(body == null ? null : body.defaults());
        boolean changed = administration.setDefaults(desired);
        if (changed) {
            log.info("ADMIN admin массово обновил общие MODERATOR scopes через API. Сессии MODERATOR инвалидированы.");
            audit.success("MODERATOR_SCOPE_DEFAULTS_BULK", admin, "moderator-defaults", "updated;sessions-invalidated");
        }
        return new ApiDataResponse<>(new ModeratorActionResponse(null, null,
            changed ? "Общие разрешения сохранены. Активные сессии модераторов завершены." : "Общие разрешения не изменились."));
    }

    @PostMapping("/{id}")
    ApiDataResponse<ModeratorActionResponse> assign(
        @PathVariable Long id,
        @RequestBody ModeratorAssignmentRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User admin = requireAdminAndPassword(authentication, body == null ? null : body.currentPassword(), request, "MODERATOR_ASSIGN", "user:" + id);
        try {
            User moderator = administration.assignModerator(id);
            log.info("ADMIN admin назначил {} модератором через API.", moderator.getUsername());
            audit.success("MODERATOR_ASSIGN", admin, "user:" + moderator.getId(), "role:MODERATOR;session-invalidated");
            return action(moderator, "Роль MODERATOR назначена. Активные сессии пользователя завершены.");
        } catch (IllegalArgumentException exception) {
            throw invalid("Операция с модератором не выполнена. Проверьте выбранного пользователя и данные.");
        }
    }

    @DeleteMapping("/{id}")
    ApiDataResponse<ModeratorActionResponse> remove(
        @PathVariable Long id,
        @RequestBody(required = false) ModeratorAssignmentRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User admin = requireAdminAndPassword(authentication, body == null ? null : body.currentPassword(), request, "MODERATOR_REMOVE", "user:" + id);
        try {
            User player = administration.removeModerator(id);
            log.info("ADMIN admin снял роль MODERATOR с {} через API и сбросил персональные overrides.", player.getUsername());
            audit.success("MODERATOR_REMOVE", admin, "user:" + player.getId(), "role:PLAYER;overrides-reset;session-invalidated");
            return action(player, "Роль MODERATOR снята. Персональные overrides сброшены.");
        } catch (IllegalArgumentException exception) {
            throw invalid("Операция с модератором не выполнена. Проверьте выбранного пользователя и данные.");
        }
    }

    @PutMapping("/{id}/scopes")
    ApiDataResponse<ModeratorActionResponse> updateOverrides(
        @PathVariable Long id,
        @RequestBody ModeratorOverridesUpdateRequest body,
        Authentication authentication,
        HttpServletRequest request
    ) {
        User admin = requireAdminAndPassword(authentication, body == null ? null : body.currentPassword(), request, "MODERATOR_SCOPE_OVERRIDES_BULK", "user:" + id);
        Map<ModeratorScope, ScopeOverrideDecision> desired = parseOverrides(body == null ? null : body.scopes());
        try {
            boolean changed = administration.setOverrides(id, desired);
            User moderator = users.findById(id).orElseThrow(() -> new IllegalArgumentException("Модератор не найден."));
            if (changed) {
                log.info("ADMIN admin массово обновил overrides MODERATOR id={} через API.", id);
                audit.success("MODERATOR_SCOPE_OVERRIDES_BULK", admin, "user:" + id, "updated;session-invalidated");
            }
            return action(moderator, changed ? "Разрешения модератора сохранены. Его активные сессии завершены." : "Разрешения не изменились.");
        } catch (IllegalArgumentException exception) {
            throw invalid("Операция с модератором не выполнена. Проверьте выбранного пользователя и данные.");
        }
    }

    private ModeratorResponse toModerator(AdminUserSummaryProjection moderator, Map<ModeratorScope, Boolean> defaults) {
        Map<ModeratorScope, ScopeOverrideDecision> decisions = administration.overrideMap(moderator.id());
        Map<String, ModeratorScopeCellResponse> scopes = new LinkedHashMap<>();
        for (ModeratorScope scope : ModeratorScope.values()) {
            ScopeOverrideDecision decision = decisions.getOrDefault(scope, ScopeOverrideDecision.INHERIT);
            boolean effective = switch (decision) {
                case ALLOW -> true;
                case DENY -> false;
                case INHERIT -> defaults.getOrDefault(scope, false);
            };
            scopes.put(scope.name(), new ModeratorScopeCellResponse(decision.name(), effective));
        }
        return new ModeratorResponse(moderator.id(), moderator.username(), scopes);
    }

    private Map<ModeratorScope, Boolean> parseDefaults(Map<String, Boolean> input) {
        if (input == null || input.size() != ModeratorScope.values().length) {
            throw invalid("Передайте значения всех разрешений модератора.");
        }
        Map<ModeratorScope, Boolean> result = new EnumMap<>(ModeratorScope.class);
        for (ModeratorScope scope : ModeratorScope.values()) {
            Boolean value = input.get(scope.name());
            if (value == null) {
                throw invalid("Некорректное значение разрешения " + scope.getDisplayName() + ".");
            }
            result.put(scope, value);
        }
        for (String key : input.keySet()) {
            try {
                ModeratorScope.valueOf(key);
            } catch (IllegalArgumentException exception) {
                throw invalid("Неизвестное разрешение модератора.");
            }
        }
        return result;
    }

    private Map<ModeratorScope, ScopeOverrideDecision> parseOverrides(Map<String, String> input) {
        if (input == null || input.size() != ModeratorScope.values().length) {
            throw invalid("Передайте overrides всех разрешений модератора.");
        }
        Map<ModeratorScope, ScopeOverrideDecision> result = new EnumMap<>(ModeratorScope.class);
        for (ModeratorScope scope : ModeratorScope.values()) {
            String raw = input.get(scope.name());
            try {
                result.put(scope, ScopeOverrideDecision.valueOf(raw));
            } catch (RuntimeException exception) {
                throw invalid("Некорректное значение разрешения " + scope.getDisplayName() + ".");
            }
        }
        for (String key : input.keySet()) {
            try {
                ModeratorScope.valueOf(key);
            } catch (IllegalArgumentException exception) {
                throw invalid("Неизвестное разрешение модератора.");
            }
        }
        return result;
    }

    private User requireCanonicalAdmin(Authentication authentication) {
        User admin = currentUsers.require(authentication);
        if (admin.getRole() != Role.ADMIN || !"admin".equals(admin.getUsername())) {
            audit.denied("MODERATOR_ADMIN_ACCESS", admin, "moderator-admin", "canonical-admin-only");
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Доступ разрешён только главному ADMIN.");
        }
        return admin;
    }

    private User requireAdminAndPassword(
        Authentication authentication,
        String password,
        HttpServletRequest request,
        String action,
        String target
    ) {
        User admin = requireCanonicalAdmin(authentication);
        try {
            User verified = sensitiveActions.verify(authentication, password, request);
            if (verified.getRole() != Role.ADMIN || !"admin".equals(verified.getUsername())) {
                audit.denied(action, admin, target, "canonical-admin-only");
                throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Доступ разрешён только главному ADMIN.");
            }
            return verified;
        } catch (SensitiveActionException exception) {
            audit.denied(action, admin, target, "reauth-failed");
            if (exception.reason() == SensitiveActionService.Reason.RATE_LIMITED) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "REAUTH_RATE_LIMITED", "Слишком много попыток подтверждения. Попробуйте позже.");
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "REAUTH_FAILED", "Не удалось подтвердить действие.");
        }
    }

    private ApiDataResponse<ModeratorActionResponse> action(User user, String message) {
        return new ApiDataResponse<>(new ModeratorActionResponse(user.getId(), user.getUsername(), message));
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MODERATOR_ADMIN_INVALID", message);
    }

    private String normalizeQuery(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "Поисковый запрос не может быть длиннее 64 символов.");
        }
        return value;
    }
}
