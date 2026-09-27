package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.SecuritySessionService;
import com.mafia.classicmafia.service.ModeratorAdministrationService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
class AuthorizationMatrixIntegrationTests {
    private static final String PASSWORD = "strong-password-123";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SecuritySessionService securitySessions;
    @Autowired ModeratorAdministrationService moderatorAdministration;
    @Autowired ModeratorPermissionService permissions;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (ModeratorScope scope : ModeratorScope.values()) {
            moderatorAdministration.setDefault(scope, false);
        }
    }

    @Test
    void scopedMutationsEnforceAnonymousPlayerModeratorAndAdminMatrix() throws Exception {
        User target = createUser("authz_target_" + suffix(), Role.PLAYER);
        User player = createUser("authz_player_" + suffix(), Role.PLAYER);
        User moderator = createUser("authz_mod_" + suffix(), Role.MODERATOR);
        User admin = createAdmin();

        for (ModeratorScope scope : ModeratorScope.values()) {
            assertThat(permissions.isAllowed(player, scope)).as("PLAYER %s", scope).isFalse();
            assertThat(permissions.isAllowed(moderator, scope)).as("MODERATOR baseline %s", scope).isFalse();
            assertThat(permissions.isAllowed(admin, scope)).as("ADMIN %s", scope).isTrue();
        }

        List<ScopeCase> cases = List.of(
            jsonCase(ModeratorScope.NEWS_MANAGE, () -> post("/api/v1/news"), "{\"title\":\"\",\"content\":\"\"}"),
            jsonCase(ModeratorScope.RULES_MANAGE, () -> put("/api/v1/rules"), "{\"title\":\"\",\"content\":\"\"}"),
            jsonCase(ModeratorScope.GAMES_MANAGE, () -> post("/api/v1/games"), "{\"name\":\"x\",\"gameDate\":\"2026-09-15\",\"host\":\"h\",\"winner\":\"\",\"summary\":\"s\",\"videoLinks\":[],\"tournamentId\":null,\"hiddenFromPublic\":false,\"visibilityUsernames\":[],\"players\":[]}"),
            jsonCase(ModeratorScope.SCHEDULE_MANAGE, () -> post("/api/v1/schedule"), "{\"entryType\":\"\",\"title\":\"\",\"scheduledAt\":\"2026-09-15T12:00:00\",\"host\":\"\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}"),
            jsonCase(ModeratorScope.PLAYER_RATING_MANAGE, () -> patch("/api/v1/players/" + target.getId() + "/rating"), "{}"),
            jsonCase(ModeratorScope.PLAYER_USERNAME_MANAGE, () -> patch("/api/v1/players/" + target.getId() + "/username"), reauthJson("username", "")),
            jsonCase(ModeratorScope.PLAYER_PASSWORD_RESET, () -> put("/api/v1/players/" + target.getId() + "/password"), reauthJson("password", "x")),
            jsonCase(ModeratorScope.PLAYER_AVATAR_RESET, () -> delete("/api/v1/players/" + target.getId() + "/avatar"), reauthOnlyJson()),
            jsonCase(ModeratorScope.PLAYER_DELETE, () -> delete("/api/v1/players/" + target.getId()), reauthJson("confirmation", "wrong"))
        );

        for (ScopeCase item : cases) {
            mvc.perform(item.request().get().with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("anonymous %s", item.scope()).isEqualTo(401));

            mvc.perform(item.request().get()
                    .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("PLAYER %s", item.scope()).isEqualTo(403));

            moderatorAdministration.setOverride(moderator.getId(), item.scope(), ScopeOverrideDecision.DENY);
            moderator = users.findById(moderator.getId()).orElseThrow();
            User deniedModerator = moderator;
            mvc.perform(item.request().get()
                    .with(TestAuthentication.authenticated(deniedModerator, securitySessions)).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("MODERATOR DENY %s", item.scope()).isEqualTo(403));

            moderatorAdministration.setOverride(moderator.getId(), item.scope(), ScopeOverrideDecision.ALLOW);
            moderator = users.findById(moderator.getId()).orElseThrow();
            User allowedModerator = moderator;
            mvc.perform(item.request().get()
                    .with(TestAuthentication.authenticated(allowedModerator, securitySessions)).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("MODERATOR ALLOW %s", item.scope()).isNotIn(401, 403));
        }
    }

    @Test
    void everyScopeManagedMutationEnforcesPermissionAtItsActualEndpoint() throws Exception {
        User player = createUser("authz_surface_player_" + suffix(), Role.PLAYER);
        User moderator = createUser("authz_surface_mod_" + suffix(), Role.MODERATOR);
        List<LabeledScopeCase> cases = List.of(
            labeledJsonCase("POST /api/v1/news", ModeratorScope.NEWS_MANAGE, () -> post("/api/v1/news"), "{\"title\":\"\",\"content\":\"\"}"),
            labeledJsonCase("PUT /api/v1/news/{id}", ModeratorScope.NEWS_MANAGE, () -> put("/api/v1/news/999999"), "{\"title\":\"\",\"content\":\"\"}"),
            labeledJsonCase("DELETE /api/v1/news/{id}", ModeratorScope.NEWS_MANAGE, () -> delete("/api/v1/news/999999"), "{}"),
            labeledJsonCase("PUT /api/v1/rules", ModeratorScope.RULES_MANAGE, () -> put("/api/v1/rules"), "{\"title\":\"\",\"content\":\"\"}"),
            labeledJsonCase("POST /api/v1/games", ModeratorScope.GAMES_MANAGE, () -> post("/api/v1/games"), "{\"name\":\"x\",\"gameDate\":\"2026-09-15\",\"host\":\"h\",\"winner\":\"CIVILIANS\",\"summary\":\"\",\"videoLinks\":[],\"tournamentId\":null,\"hiddenFromPublic\":false,\"visibilityUsernames\":[],\"players\":[]}"),
            labeledJsonCase("PUT /api/v1/games/{id}", ModeratorScope.GAMES_MANAGE, () -> put("/api/v1/games/999999"), "{\"name\":\"x\",\"gameDate\":\"2026-09-15\",\"host\":\"h\",\"winner\":\"CIVILIANS\",\"summary\":\"\",\"videoLinks\":[],\"tournamentId\":null,\"hiddenFromPublic\":false,\"visibilityUsernames\":[],\"players\":[]}"),
            labeledJsonCase("DELETE /api/v1/games/{id}", ModeratorScope.GAMES_MANAGE, () -> delete("/api/v1/games/999999"), "{}"),
            labeledJsonCase("POST /api/v1/tournaments", ModeratorScope.GAMES_MANAGE, () -> post("/api/v1/tournaments"), "{\"name\":\"x\",\"dates\":[\"2026-09-15\"],\"gameCount\":1,\"participationCost\":\"\",\"description\":\"\"}"),
            labeledJsonCase("PUT /api/v1/tournaments/{id}", ModeratorScope.GAMES_MANAGE, () -> put("/api/v1/tournaments/999999"), "{\"name\":\"x\",\"dates\":[\"2026-09-15\"],\"gameCount\":1,\"participationCost\":\"\",\"description\":\"\"}"),
            labeledJsonCase("DELETE /api/v1/tournaments/{id}", ModeratorScope.GAMES_MANAGE, () -> delete("/api/v1/tournaments/999999"), "{}"),
            labeledJsonCase("POST /api/v1/tournaments/{id}/invitations", ModeratorScope.GAMES_MANAGE, () -> post("/api/v1/tournaments/999999/invitations"), "{\"username\":\"x\"}"),
            labeledJsonCase("DELETE /api/v1/tournaments/{id}/invitations/{userId}", ModeratorScope.GAMES_MANAGE, () -> delete("/api/v1/tournaments/999999/invitations/999999"), "{}"),
            labeledJsonCase("POST /api/v1/schedule", ModeratorScope.SCHEDULE_MANAGE, () -> post("/api/v1/schedule"), "{\"entryType\":\"GAME\",\"title\":\"x\",\"scheduledAt\":\"2026-09-15T12:00:00\",\"host\":\"h\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}"),
            labeledJsonCase("PUT /api/v1/schedule/{id}", ModeratorScope.SCHEDULE_MANAGE, () -> put("/api/v1/schedule/999999"), "{\"entryType\":\"GAME\",\"title\":\"x\",\"scheduledAt\":\"2026-09-15T12:00:00\",\"host\":\"h\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}"),
            labeledJsonCase("DELETE /api/v1/schedule/{id}", ModeratorScope.SCHEDULE_MANAGE, () -> delete("/api/v1/schedule/999999"), "{}")
        );
        for (LabeledScopeCase item : cases) {
            mvc.perform(item.request().get().with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).as("PLAYER %s", item.label()).isEqualTo(403));
            moderatorAdministration.setOverride(moderator.getId(), item.scope(), ScopeOverrideDecision.DENY);
            User denied = users.findById(moderator.getId()).orElseThrow();
            mvc.perform(item.request().get().with(TestAuthentication.authenticated(denied, securitySessions)).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).as("MODERATOR DENY %s", item.scope()).isEqualTo(403));
            moderatorAdministration.setOverride(moderator.getId(), item.scope(), ScopeOverrideDecision.ALLOW);
            User allowed = users.findById(moderator.getId()).orElseThrow();
            mvc.perform(item.request().get().with(TestAuthentication.authenticated(allowed, securitySessions)).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).as("MODERATOR ALLOW %s", item.scope()).isNotIn(401, 403));
        }
    }

    private ScopeCase jsonCase(ModeratorScope scope, Supplier<MockHttpServletRequestBuilder> builder, String json) {
        return new ScopeCase(scope, () -> builder.get().contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private LabeledScopeCase labeledJsonCase(String label, ModeratorScope scope, Supplier<MockHttpServletRequestBuilder> builder, String json) {
        return new LabeledScopeCase(label, scope, () -> builder.get().contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String reauthJson(String field, String value) {
        return "{\"" + field + "\":\"" + value + "\",\"currentPassword\":\"" + PASSWORD + "\"}";
    }

    private String reauthOnlyJson() {
        return "{\"currentPassword\":\"" + PASSWORD + "\"}";
    }

    private User createAdmin() {
        User admin = users.findByUsernameKey("admin").orElseGet(User::new);
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        return users.saveAndFlush(admin);
    }

    private User createUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record ScopeCase(
        ModeratorScope scope,
        Supplier<MockHttpServletRequestBuilder> request
    ) {}

    private record LabeledScopeCase(
        String label,
        ModeratorScope scope,
        Supplier<MockHttpServletRequestBuilder> request
    ) {}
}
