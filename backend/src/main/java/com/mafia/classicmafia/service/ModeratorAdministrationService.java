package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.ModeratorScopeDefault;
import com.mafia.classicmafia.entity.ModeratorScopeOverride;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.ModeratorScopeDefaultRepository;
import com.mafia.classicmafia.repository.ModeratorScopeOverrideRepository;
import com.mafia.classicmafia.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class ModeratorAdministrationService {
    private final UserRepository users;
    private final ModeratorScopeDefaultRepository defaults;
    private final ModeratorScopeOverrideRepository overrides;

    public ModeratorAdministrationService(
        UserRepository users,
        ModeratorScopeDefaultRepository defaults,
        ModeratorScopeOverrideRepository overrides
    ) {
        this.users = users;
        this.defaults = defaults;
        this.overrides = overrides;
    }

    @Transactional
    public void ensureDefaultRows() {
        for (ModeratorScope scope : ModeratorScope.values()) {
            defaults.findById(scope).orElseGet(() -> {
                ModeratorScopeDefault item = new ModeratorScopeDefault();
                item.setScope(scope);
                item.setEnabled(false);
                return defaults.save(item);
            });
        }
    }

    @Transactional
    public User assignModerator(Long userId) {
        User user = requireNonAdmin(userId);
        if (user.getRole() != Role.MODERATOR) {
            user.setRole(Role.MODERATOR);
            user.incrementAuthorizationVersion();
            users.saveAndFlush(user);
        }
        ensureOverrideRows(user);
        return user;
    }

    @Transactional
    public User removeModerator(Long userId) {
        User user = users.findById(userId)
            .filter(candidate -> candidate.getRole() == Role.MODERATOR)
            .orElseThrow(() -> new IllegalArgumentException("Модератор не найден."));
        user.setRole(Role.PLAYER);
        user.incrementAuthorizationVersion();
        users.saveAndFlush(user);
        overrides.deleteAllByUserId(userId);
        return user;
    }

    @Transactional
    public boolean setDefault(ModeratorScope scope, boolean enabled) {
        ModeratorScopeDefault item = defaults.findById(scope).orElseGet(() -> {
            ModeratorScopeDefault created = new ModeratorScopeDefault();
            created.setScope(scope);
            created.setEnabled(false);
            return created;
        });
        if (item.isEnabled() == enabled) {
            defaults.save(item);
            return false;
        }
        item.setEnabled(enabled);
        defaults.save(item);
        users.incrementAuthorizationVersionForRole(Role.MODERATOR);
        return true;
    }

    @Transactional
    public boolean setDefaults(Map<ModeratorScope, Boolean> desiredValues) {
        boolean changed = false;
        for (ModeratorScope scope : ModeratorScope.values()) {
            boolean desired = desiredValues.getOrDefault(scope, false);
            ModeratorScopeDefault item = defaults.findById(scope).orElseGet(() -> {
                ModeratorScopeDefault created = new ModeratorScopeDefault();
                created.setScope(scope);
                created.setEnabled(false);
                return created;
            });
            if (item.isEnabled() != desired) {
                item.setEnabled(desired);
                changed = true;
            }
            defaults.save(item);
        }
        if (changed) {
            users.incrementAuthorizationVersionForRole(Role.MODERATOR);
        }
        return changed;
    }

    @Transactional
    public boolean setOverride(Long userId, ModeratorScope scope, ScopeOverrideDecision decision) {
        User user = users.findById(userId)
            .filter(candidate -> candidate.getRole() == Role.MODERATOR)
            .orElseThrow(() -> new IllegalArgumentException("Модератор не найден."));
        ModeratorScopeOverride item = overrides.findByUserIdAndScope(userId, scope).orElseGet(() -> {
            ModeratorScopeOverride created = new ModeratorScopeOverride();
            created.setUser(user);
            created.setScope(scope);
            created.setDecision(ScopeOverrideDecision.INHERIT);
            return created;
        });
        ScopeOverrideDecision normalized = decision == null ? ScopeOverrideDecision.INHERIT : decision;
        if (item.getDecision() == normalized) {
            overrides.save(item);
            return false;
        }
        item.setDecision(normalized);
        overrides.save(item);
        user.incrementAuthorizationVersion();
        users.saveAndFlush(user);
        return true;
    }

    @Transactional
    public boolean setOverrides(Long userId, Map<ModeratorScope, ScopeOverrideDecision> decisions) {
        User user = users.findById(userId)
            .filter(candidate -> candidate.getRole() == Role.MODERATOR)
            .orElseThrow(() -> new IllegalArgumentException("Модератор не найден."));
        ensureOverrideRows(user);
        boolean changed = false;
        for (ModeratorScope scope : ModeratorScope.values()) {
            ScopeOverrideDecision desired = decisions.getOrDefault(scope, ScopeOverrideDecision.INHERIT);
            ModeratorScopeOverride item = overrides.findByUserIdAndScope(userId, scope).orElseThrow();
            if (item.getDecision() != desired) {
                item.setDecision(desired);
                overrides.save(item);
                changed = true;
            }
        }
        if (changed) {
            user.incrementAuthorizationVersion();
            users.saveAndFlush(user);
        }
        return changed;
    }

    @Transactional(readOnly = true)
    public Map<ModeratorScope, Boolean> defaultMap() {
        Map<ModeratorScope, Boolean> result = new EnumMap<>(ModeratorScope.class);
        for (ModeratorScope scope : ModeratorScope.values()) {
            result.put(scope, false);
        }
        for (ModeratorScopeDefault item : defaults.findAll()) {
            result.put(item.getScope(), item.isEnabled());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<ModeratorScope, ScopeOverrideDecision> overrideMap(Long userId) {
        Map<ModeratorScope, ScopeOverrideDecision> result = new EnumMap<>(ModeratorScope.class);
        for (ModeratorScope scope : ModeratorScope.values()) {
            result.put(scope, ScopeOverrideDecision.INHERIT);
        }
        for (ModeratorScopeOverride item : overrides.findAllByUserId(userId)) {
            result.put(item.getScope(), item.getDecision());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<User> moderators() {
        return users.findAllByRoleOrderByUsernameAsc(Role.MODERATOR);
    }

    public List<ModeratorScope> scopes() {
        return Arrays.asList(ModeratorScope.values());
    }

    private User requireNonAdmin(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));
        if (user.getRole() == Role.ADMIN || "admin".equals(user.getUsernameKey())) {
            throw new IllegalArgumentException("Главного ADMIN нельзя изменять через управление модераторами.");
        }
        return user;
    }

    private void ensureOverrideRows(User user) {
        for (ModeratorScope scope : ModeratorScope.values()) {
            if (overrides.findByUserIdAndScope(user.getId(), scope).isEmpty()) {
                ModeratorScopeOverride item = new ModeratorScopeOverride();
                item.setUser(user);
                item.setScope(scope);
                item.setDecision(ScopeOverrideDecision.INHERIT);
                overrides.save(item);
            }
        }
    }
}
