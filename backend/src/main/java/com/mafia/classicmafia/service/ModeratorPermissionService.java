package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.ModeratorScopeDefaultRepository;
import com.mafia.classicmafia.repository.ModeratorScopeOverrideRepository;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

@Service
public class ModeratorPermissionService {
    private final CurrentUserService currentUserService;
    private final ModeratorScopeDefaultRepository defaults;
    private final ModeratorScopeOverrideRepository overrides;

    public ModeratorPermissionService(
        CurrentUserService currentUserService,
        ModeratorScopeDefaultRepository defaults,
        ModeratorScopeOverrideRepository overrides
    ) {
        this.currentUserService = currentUserService;
        this.defaults = defaults;
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(Authentication authentication, ModeratorScope scope) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        SecurityAccountProjection user;
        try {
            user = currentUserService.requireSecurity(authentication);
        } catch (RuntimeException exception) {
            return false;
        }
        return isAllowed(user.id(), user.role(), scope);
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(User user, ModeratorScope scope) {
        return isAllowed(user.getId(), user.getRole(), scope);
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(long userId, Role role, ModeratorScope scope) {
        if (role == Role.ADMIN) {
            return true;
        }
        if (role != Role.MODERATOR) {
            return false;
        }
        var override = overrides.findByUserIdAndScope(userId, scope)
            .map(item -> item.getDecision())
            .orElse(ScopeOverrideDecision.INHERIT);
        if (override == ScopeOverrideDecision.ALLOW) {
            return true;
        }
        if (override == ScopeOverrideDecision.DENY) {
            return false;
        }
        return defaults.findById(scope).map(item -> item.isEnabled()).orElse(false);
    }

    public void require(Authentication authentication, ModeratorScope scope) {
        if (!isAllowed(authentication, scope)) {
            throw new AccessDeniedException("Недостаточно прав.");
        }
    }

    @Transactional(readOnly = true)
    public Map<ModeratorScope, Boolean> effectivePermissions(Authentication authentication) {
        Map<ModeratorScope, Boolean> result = new EnumMap<>(ModeratorScope.class);
        for (ModeratorScope scope : ModeratorScope.values()) {
            result.put(scope, false);
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            return result;
        }

        SecurityAccountProjection user;
        try {
            user = currentUserService.requireSecurity(authentication);
        } catch (RuntimeException exception) {
            return result;
        }
        if (user.role() == Role.ADMIN) {
            result.replaceAll((scope, ignored) -> true);
            return result;
        }
        if (user.role() != Role.MODERATOR) {
            return result;
        }

        for (var item : defaults.findAll()) {
            result.put(item.getScope(), item.isEnabled());
        }
        for (var item : overrides.findAllByUserId(user.id())) {
            if (item.getDecision() == ScopeOverrideDecision.ALLOW) {
                result.put(item.getScope(), true);
            } else if (item.getDecision() == ScopeOverrideDecision.DENY) {
                result.put(item.getScope(), false);
            }
        }
        return result;
    }

}
