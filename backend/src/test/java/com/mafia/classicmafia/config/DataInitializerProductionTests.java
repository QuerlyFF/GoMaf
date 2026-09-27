package com.mafia.classicmafia.config;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.RulesImportState;
import com.mafia.classicmafia.entity.RulesPage;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.RulesImportStateRepository;
import com.mafia.classicmafia.repository.RulesPageRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.PasswordPolicy;
import com.mafia.classicmafia.service.ModeratorAdministrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitializerProductionTests {
    @Mock UserRepository users;
    @Mock RulesPageRepository rules;
    @Mock RulesImportStateRepository rulesImportState;
    @Mock ModeratorAdministrationService moderatorAdministration;

    @Test
    void productionRequiresExactAdminUsername() {
        var initializer = newInitializer();
        ReflectionTestUtils.setField(initializer, "adminUsername", "ProdAdmin");
        ReflectionTestUtils.setField(initializer, "adminPassword", "production-pass-12345");
        ReflectionTestUtils.setField(initializer, "environment", "production");
        stubNonAdminStartupState();

        assertThatThrownBy(initializer::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("строго admin");
    }

    @Test
    void existingProductionAdminPasswordIsSynchronizedAndSessionVersionBumped() {
        var encoder = new BCryptPasswordEncoder(4);
        var initializer = new DataInitializer(
            users,
            rules,
            rulesImportState,
            encoder,
            new PasswordPolicy(),
            moderatorAdministration
        );
        ReflectionTestUtils.setField(initializer, "adminUsername", "admin");
        ReflectionTestUtils.setField(initializer, "adminPassword", "production-pass-12345");
        ReflectionTestUtils.setField(initializer, "environment", "production");

        User admin = new User();
        ReflectionTestUtils.setField(admin, "id", 10L);
        admin.setUsername("admin");
        admin.setPassword(encoder.encode("old-production-password"));
        admin.setRole(Role.ADMIN);

        when(users.findAll()).thenReturn(List.of(admin));
        when(users.findByUsernameKey("admin")).thenReturn(Optional.of(admin));
        when(users.countByRole(Role.ADMIN)).thenReturn(1L);
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubRulesState();

        initializer.run();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getCredentialVersion()).isEqualTo(1);
        assertThat(encoder.matches("production-pass-12345", admin.getPassword())).isTrue();
        verify(moderatorAdministration).ensureDefaultRows();
    }

    @Test
    void reservedAdminUsernameOwnedByPlayerStopsStartup() {
        var initializer = newInitializer();
        ReflectionTestUtils.setField(initializer, "adminUsername", "admin");
        ReflectionTestUtils.setField(initializer, "adminPassword", "production-pass-12345");
        ReflectionTestUtils.setField(initializer, "environment", "production");

        User player = new User();
        ReflectionTestUtils.setField(player, "id", 22L);
        player.setUsername("admin");
        player.setPassword("encoded");
        player.setRole(Role.PLAYER);

        when(users.findAll()).thenReturn(List.of(player));
        when(users.findByUsernameKey("admin")).thenReturn(Optional.of(player));
        stubRulesState();

        assertThatThrownBy(initializer::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("занят не-ADMIN");
    }

    private DataInitializer newInitializer() {
        return new DataInitializer(
            users,
            rules,
            rulesImportState,
            new BCryptPasswordEncoder(4),
            new PasswordPolicy(),
            moderatorAdministration
        );
    }

    private void stubNonAdminStartupState() {
        when(users.findAll()).thenReturn(List.of());
        stubRulesState();
    }

    private void stubRulesState() {
        RulesPage page = new RulesPage();
        RulesImportState state = new RulesImportState();
        state.setVersion(1);
        when(rules.findById(1L)).thenReturn(Optional.of(page));
        when(rulesImportState.findById(1L)).thenReturn(Optional.of(state));
    }
}
