package com.mafia.classicmafia.e2e;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.service.ModeratorAdministrationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class E2eDataInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ModeratorAdministrationService moderators;

    public E2eDataInitializer(
        UserRepository users,
        PasswordEncoder passwordEncoder,
        ModeratorAdministrationService moderators
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.moderators = moderators;
    }

    @Override
    public void run(ApplicationArguments args) {
        upsert("admin", "e2e-admin-password", Role.ADMIN, 2000.0);
        User moderator = upsert("e2e_moderator", "e2e-moderator-password", Role.MODERATOR, 1500.0);
        upsert("e2e_other_moderator", "e2e-other-moderator-password", Role.MODERATOR, 1450.0);
        upsert("e2e_target", "e2e-target-password", Role.PLAYER, 1300.0);
        upsert("e2e_very_long_player_name_123456", "e2e-long-password", Role.PLAYER, 1200.0);
        moderators.setOverride(moderator.getId(), ModeratorScope.PLAYER_RATING_MANAGE, ScopeOverrideDecision.ALLOW);
    }

    private User upsert(String username, String password, Role role, double rating) {
        User user = users.findByUsernameKey(User.canonicalUsername(username)).orElseGet(User::new);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        user.setRating(rating);
        return users.saveAndFlush(user);
    }
}
