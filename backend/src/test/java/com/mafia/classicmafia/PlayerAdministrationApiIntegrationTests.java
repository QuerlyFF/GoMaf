package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.MafiaUserPrincipal;
import com.mafia.classicmafia.security.SecuritySessionService;
import com.mafia.classicmafia.service.ModeratorAdministrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PlayerAdministrationApiIntegrationTests {
    private static final String PASSWORD = "strong-password-123";
    private static final AtomicInteger CLIENT_IP_SEQUENCE = new AtomicInteger(1);

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SecuritySessionService securitySessions;
    @Autowired ModeratorAdministrationService moderatorAdministration;

    private MockMvc mvc;
    private String clientIp;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clientIp = "2001:db8:" + Integer.toHexString(CLIENT_IP_SEQUENCE.getAndIncrement()) + ":1::1";
    }

    @Test
    void anonymousAdministrationMutationUsesJson401() throws Exception {
        User player = createUser("wp11007_anon_" + suffix(), Role.PLAYER);
        mvc.perform(patch("/api/v1/players/" + player.getId() + "/rating")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1234}"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void administrationMutationRequiresCsrf() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11007_csrf_" + suffix(), Role.PLAYER);
        mvc.perform(patch("/api/v1/players/" + player.getId() + "/rating")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1234}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void capabilitiesAreBackendOwnedWithoutDisclosingTargetRole() throws Exception {
        User admin = createAdmin();
        User targetModerator = createUser("wp11007_caps_mod_" + suffix(), Role.MODERATOR);

        mvc.perform(get("/api/v1/players/" + targetModerator.getId() + "/administration")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manageable").value(true))
            .andExpect(jsonPath("$.data.rating").value(true))
            .andExpect(jsonPath("$.data.username").value(true))
            .andExpect(jsonPath("$.data.password").value(true))
            .andExpect(jsonPath("$.data.avatarReset").value(true))
            .andExpect(jsonPath("$.data.profileEdit").value(true))
            .andExpect(jsonPath("$.data.deleteAccount").value(true))
            .andExpect(jsonPath("$.data.role").doesNotExist());

        User ordinary = createUser("wp11007_caps_player_" + suffix(), Role.PLAYER);
        mvc.perform(get("/api/v1/players/" + ordinary.getId() + "/administration")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(ordinary, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manageable").value(false))
            .andExpect(jsonPath("$.data.rating").value(false))
            .andExpect(jsonPath("$.data.deleteAccount").value(false));
    }

    @Test
    void adminCanUpdateRatingWithoutSensitiveReauthentication() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11007_rating_" + suffix(), Role.PLAYER);

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/rating")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1777.5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.playerId").value(player.getId()))
            .andExpect(jsonPath("$.data.username").value(player.getUsername()));

        assertThat(users.findById(player.getId()).orElseThrow().getRating()).isEqualTo(1777.5);
    }

    @Test
    void usernameUpdateRequiresCurrentOperatorPasswordAndInvalidatesTargetSessionVersion() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11007_before_" + suffix(), Role.PLAYER);
        int oldVersion = player.getCredentialVersion();
        String replacement = "wp11007_after_" + suffix();
        var adminAuth = TestAuthentication.authenticated(admin, securitySessions);

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/username")
                .with(isolatedClientIp())
                .with(adminAuth)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + replacement + "\",\"currentPassword\":\"wrong-password\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REAUTH_FAILED"));
        assertThat(users.findById(player.getId()).orElseThrow().getUsername()).isEqualTo(player.getUsername());

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/username")
                .with(isolatedClientIp())
                .with(adminAuth)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + replacement + "\",\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(replacement));

        User changed = users.findById(player.getId()).orElseThrow();
        assertThat(changed.getUsername()).isEqualTo(replacement);
        assertThat(changed.getCredentialVersion()).isEqualTo(oldVersion + 1);
    }

    @Test
    void passwordResetUsesSharedPasswordPolicyAndInvalidatesTargetSessionVersion() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11007_password_" + suffix(), Role.PLAYER);
        int oldVersion = player.getCredentialVersion();
        var adminAuth = TestAuthentication.authenticated(admin, securitySessions);

        mvc.perform(put("/api/v1/players/" + player.getId() + "/password")
                .with(isolatedClientIp())
                .with(adminAuth)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"123\",\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PLAYER_ADMIN_INVALID"));

        mvc.perform(put("/api/v1/players/" + player.getId() + "/password")
                .with(isolatedClientIp())
                .with(adminAuth)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"replacement-password-123\",\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());

        User changed = users.findById(player.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("replacement-password-123", changed.getPassword())).isTrue();
        assertThat(changed.getCredentialVersion()).isEqualTo(oldVersion + 1);
    }

    @Test
    void avatarResetRequiresReauthenticationAndClearsSafeAvatar() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11007_avatar_" + suffix(), Role.PLAYER);
        player.setAvatarData(new byte[] {1, 2, 3});
        player.setAvatarContentType("image/png");
        player.setAvatarSafetyVersion(1);
        users.saveAndFlush(player);

        mvc.perform(delete("/api/v1/players/" + player.getId() + "/avatar")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());

        User changed = users.findById(player.getId()).orElseThrow();
        assertThat(changed.getAvatarData()).isNull();
        assertThat(changed.getAvatarContentType()).isNull();
        assertThat(changed.getAvatarSafetyVersion()).isNull();
    }

    @Test
    void profileEditIsAdminOnlyAndBounded() throws Exception {
        User admin = createAdmin();
        User moderator = createUser("wp11007_profile_mod_" + suffix(), Role.MODERATOR);
        User player = createUser("wp11007_profile_" + suffix(), Role.PLAYER);
        for (ModeratorScope scope : ModeratorScope.values()) {
            moderatorAdministration.setOverride(moderator.getId(), scope, ScopeOverrideDecision.ALLOW);
        }
        moderator = users.findById(moderator.getId()).orElseThrow();

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/profile")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(moderator, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aboutMe\":\"forbidden\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PLAYER_ADMIN_FORBIDDEN"));

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/profile")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aboutMe\":\"API profile text\"}"))
            .andExpect(status().isOk());
        assertThat(users.findById(player.getId()).orElseThrow().getAboutMe()).isEqualTo("API profile text");

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/profile")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aboutMe\":\"" + "x".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PLAYER_ADMIN_INVALID"));
    }

    @Test
    void moderatorScopeCanManageOrdinaryPlayerButNeverSelfOrAnotherModerator() throws Exception {
        User moderator = createUser("wp11007_operator_" + suffix(), Role.MODERATOR);
        moderatorAdministration.setOverride(moderator.getId(), ModeratorScope.PLAYER_RATING_MANAGE, ScopeOverrideDecision.ALLOW);
        moderator = users.findById(moderator.getId()).orElseThrow();
        User player = createUser("wp11007_target_" + suffix(), Role.PLAYER);
        User otherModerator = createUser("wp11007_other_mod_" + suffix(), Role.MODERATOR);

        mvc.perform(patch("/api/v1/players/" + player.getId() + "/rating")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(moderator, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1555}"))
            .andExpect(status().isOk());

        mvc.perform(patch("/api/v1/players/" + moderator.getId() + "/rating")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(moderator, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1666}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PLAYER_ADMIN_FORBIDDEN"));

        mvc.perform(patch("/api/v1/players/" + otherModerator.getId() + "/rating")
                .with(isolatedClientIp())
                .with(TestAuthentication.authenticated(moderator, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1666}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PLAYER_ADMIN_FORBIDDEN"));
    }

    @Test
    void deleteRequiresExactUsernameAndCurrentPassword() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11007_delete_" + suffix(), Role.PLAYER);
        Long id = player.getId();
        var adminAuth = TestAuthentication.authenticated(admin, securitySessions);

        mvc.perform(delete("/api/v1/players/" + id)
                .with(isolatedClientIp())
                .with(adminAuth)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"confirmation\":\"wrong\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PLAYER_ADMIN_INVALID"));
        assertThat(users.findById(id)).isPresent();

        mvc.perform(delete("/api/v1/players/" + id)
                .with(isolatedClientIp())
                .with(adminAuth)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"confirmation\":\"" + player.getUsername() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.playerId").value(id));
        assertThat(users.findById(id)).isEmpty();
    }

    private User createAdmin() {
        User admin = users.findByUsernameKey("admin").orElseGet(User::new);
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        admin = users.saveAndFlush(admin);
        return admin;
    }

    private User createUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private MafiaUserPrincipal principal(User user) {
        return new MafiaUserPrincipal(
            user.getId(),
            user.getUsername(),
            user.getPassword(),
            user.getRole().name(),
            user.getCredentialVersion(),
            user.getAuthorizationVersion()
        );
    }

    private UsernamePasswordAuthenticationToken authToken(MafiaUserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
            principal,
            principal.getPassword(),
            List.of(new SimpleGrantedAuthority("ROLE_" + principal.roleName()))
        );
    }

    private RequestPostProcessor isolatedClientIp() {
        return request -> {
            request.setRemoteAddr(clientIp);
            return request;
        };
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
