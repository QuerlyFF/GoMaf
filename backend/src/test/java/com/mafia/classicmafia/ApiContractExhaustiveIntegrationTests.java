package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.SecuritySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class ApiContractExhaustiveIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SecuritySessionService securitySessions;

    private MockMvc mvc;
    private User player;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        player = user("contract_exhaustive_" + suffix(), Role.PLAYER);
    }
    private static final String GAME_JSON = "{\"name\":\"x\",\"gameDate\":\"2026-09-15\",\"host\":\"h\",\"winner\":\"CIVILIANS\",\"summary\":\"\",\"videoLinks\":[],\"tournamentId\":null,\"hiddenFromPublic\":false,\"visibilityUsernames\":[],\"players\":[{\"seatNumber\":1,\"username\":\"guest\",\"role\":\"CIVILIAN\",\"firstKilled\":false,\"bestMove1\":null,\"bestMove2\":null,\"bestMove3\":null,\"bonusPoints\":\"0\",\"penaltyPoints\":\"0\"}]}";
    private static final String TOURNAMENT_JSON = "{\"name\":\"x\",\"dates\":[\"2026-09-15\"],\"gameCount\":1,\"participationCost\":\"\",\"description\":\"\"}";
    private static final String SCHEDULE_JSON = "{\"entryType\":\"GAME\",\"title\":\"x\",\"scheduledAt\":\"2026-09-15T12:00:00\",\"host\":\"h\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}";
    private static final String REAUTH_JSON = "{\"currentPassword\":\"strong-password-123\"}";

    @Test
    void everyNumericPathParameterRejectsWrongTypeWithStable400() throws Exception {
        List<PathCase> cases = List.of(
            path(HttpMethod.GET, "/api/v1/players/not-a-number", null),
            path(HttpMethod.GET, "/api/v1/players/not-a-number/games", null),
            path(HttpMethod.GET, "/api/v1/players/not-a-number/avatar", null),
            path(HttpMethod.GET, "/api/v1/players/not-a-number/banner", null),
            path(HttpMethod.GET, "/api/v1/games/not-a-number", null),
            path(HttpMethod.PUT, "/api/v1/games/not-a-number", GAME_JSON),
            path(HttpMethod.DELETE, "/api/v1/games/not-a-number", null)
        );
        cases = new java.util.ArrayList<>(cases);
        cases.addAll(List.of(
            path(HttpMethod.POST, "/api/v1/admin/moderators/not-a-number", REAUTH_JSON),
            path(HttpMethod.DELETE, "/api/v1/admin/moderators/not-a-number", REAUTH_JSON),
            path(HttpMethod.PUT, "/api/v1/admin/moderators/not-a-number/scopes", "{\"scopes\":{},\"currentPassword\":\"strong-password-123\"}"),
            path(HttpMethod.GET, "/api/v1/players/not-a-number/administration", null),
            path(HttpMethod.PATCH, "/api/v1/players/not-a-number/rating", "{\"rating\":1}"),
            path(HttpMethod.PATCH, "/api/v1/players/not-a-number/username", "{\"username\":\"valid_name\",\"currentPassword\":\"strong-password-123\"}"),
            path(HttpMethod.PUT, "/api/v1/players/not-a-number/password", "{\"password\":\"new-strong-password-123\",\"currentPassword\":\"strong-password-123\"}"),
            path(HttpMethod.DELETE, "/api/v1/players/not-a-number/avatar", REAUTH_JSON),
            path(HttpMethod.PATCH, "/api/v1/players/not-a-number/profile", "{\"aboutMe\":\"x\"}"),
            path(HttpMethod.DELETE, "/api/v1/players/not-a-number", "{\"currentPassword\":\"strong-password-123\",\"confirmation\":\"x\"}"),
            path(HttpMethod.PUT, "/api/v1/schedule/not-a-number", SCHEDULE_JSON),
            path(HttpMethod.DELETE, "/api/v1/schedule/not-a-number", null),
            path(HttpMethod.POST, "/api/v1/schedule/not-a-number/participation", null)
        ));
        cases.addAll(List.of(
            path(HttpMethod.GET, "/api/v1/tournaments/not-a-number", null),
            path(HttpMethod.PUT, "/api/v1/tournaments/not-a-number", TOURNAMENT_JSON),
            path(HttpMethod.DELETE, "/api/v1/tournaments/not-a-number", null),
            path(HttpMethod.POST, "/api/v1/tournaments/not-a-number/participation", null),
            path(HttpMethod.POST, "/api/v1/tournaments/not-a-number/invitations", "{\"username\":\"guest\"}"),
            path(HttpMethod.DELETE, "/api/v1/tournaments/not-a-number/invitations/1", null),
            path(HttpMethod.DELETE, "/api/v1/tournaments/1/invitations/not-a-number", null),
            path(HttpMethod.PUT, "/api/v1/news/not-a-number", "{\"title\":\"x\",\"content\":\"y\"}"),
            path(HttpMethod.DELETE, "/api/v1/news/not-a-number", null)
        ));

        for (PathCase item : cases) {
            MockHttpServletRequestBuilder builder = request(item.method(), item.path())
                .with(TestAuthentication.authenticated(player, securitySessions));
            if (item.method() != HttpMethod.GET) builder.with(csrf());
            if (item.body() != null) builder.contentType(MediaType.APPLICATION_JSON).content(item.body());
            mvc.perform(builder)
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("%s %s", item.method(), item.path()).isEqualTo(400))
                .andExpect(jsonPath("$.code").value("PARAMETER_TYPE_INVALID"));
        }
    }
    @Test
    void everyRequiredJsonBodyEndpointRejectsMissingMalformedAndWrongTopLevelJson() throws Exception {
        List<BodyCase> cases = List.of(
            body(HttpMethod.POST, "/api/v1/auth/login", false),
            body(HttpMethod.POST, "/api/v1/auth/register", false),
            body(HttpMethod.POST, "/api/v1/news", true),
            body(HttpMethod.PUT, "/api/v1/news/999999", true),
            body(HttpMethod.PUT, "/api/v1/rules", true),
            body(HttpMethod.POST, "/api/v1/games", true),
            body(HttpMethod.PUT, "/api/v1/games/999999", true),
            body(HttpMethod.PUT, "/api/v1/admin/moderators/defaults", true),
            body(HttpMethod.POST, "/api/v1/admin/moderators/999999", true),
            body(HttpMethod.PUT, "/api/v1/admin/moderators/999999/scopes", true)
        );
        cases = new java.util.ArrayList<>(cases);
        cases.addAll(List.of(
            body(HttpMethod.PATCH, "/api/v1/players/999999/rating", true),
            body(HttpMethod.PATCH, "/api/v1/players/999999/username", true),
            body(HttpMethod.PUT, "/api/v1/players/999999/password", true),
            body(HttpMethod.PATCH, "/api/v1/players/999999/profile", true),
            body(HttpMethod.DELETE, "/api/v1/players/999999", true),
            body(HttpMethod.PATCH, "/api/v1/profile/about", true),
            body(HttpMethod.POST, "/api/v1/schedule", true),
            body(HttpMethod.PUT, "/api/v1/schedule/999999", true),
            body(HttpMethod.POST, "/api/v1/tournaments", true),
            body(HttpMethod.PUT, "/api/v1/tournaments/999999", true),
            body(HttpMethod.POST, "/api/v1/tournaments/999999/invitations", true)
        ));

        for (BodyCase item : cases) {
            assertJsonInvalid(item, null);
            assertJsonInvalid(item, "{");
            assertJsonInvalid(item, "[]");
        }
    }
    @Test
    void listAndQueryBoundariesAreConsistentAcrossEveryQueryableEndpoint() throws Exception {
        User target = user("query_target_" + suffix(), Role.PLAYER);
        User admin = canonicalAdmin();

        assertOkAndBadSize("/api/v1/games", null);
        assertOkAndBadSize("/api/v1/rating?sort=points", null);
        assertOkAndBadSize("/api/v1/players/" + target.getId() + "/games", null);
        assertOkAndBadSize("/api/v1/profile/games", player);
        assertOkAndBadSize("/api/v1/admin/moderators", admin);
        assertOkAndBadSize("/api/v1/admin/moderators/eligible", admin);

        mvc.perform(request(HttpMethod.GET, "/api/v1/games?q=" + "x".repeat(80))).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/v1/games?q=" + "x".repeat(81)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUERY_TOO_LONG"));

        mvc.perform(request(HttpMethod.GET, "/api/v1/rating?sort=weighted&size=1&page=0")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/v1/rating?sort=invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SORT_INVALID"));

        assertAdminQueryStrict("/api/v1/admin/moderators", admin);
        assertAdminQueryStrict("/api/v1/admin/moderators/eligible", admin);

        mvc.perform(request(HttpMethod.GET, "/api/v1/tournaments?view=upcoming")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/v1/tournaments?view=completed")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/v1/tournaments?view=invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VIEW_INVALID"));
    }

    private void assertOkAndBadSize(String base, User actor) throws Exception {
        String separator = base.contains("?") ? "&" : "?";
        for (int size : new int[]{1, 100}) {
            MockHttpServletRequestBuilder ok = request(HttpMethod.GET, base + separator + "size=" + size + "&page=0");
            if (actor != null) ok.with(TestAuthentication.authenticated(actor, securitySessions));
            mvc.perform(ok).andExpect(status().isOk());
        }
        for (int size : new int[]{0, 101}) {
            MockHttpServletRequestBuilder bad = request(HttpMethod.GET, base + separator + "size=" + size);
            if (actor != null) bad.with(TestAuthentication.authenticated(actor, securitySessions));
            mvc.perform(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_SIZE_INVALID"));
        }
        MockHttpServletRequestBuilder negative = request(HttpMethod.GET, base + separator + "page=-1");
        if (actor != null) negative.with(TestAuthentication.authenticated(actor, securitySessions));
        mvc.perform(negative).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_INVALID"));
    }

    private void assertAdminQueryStrict(String base, User admin) throws Exception {
        mvc.perform(request(HttpMethod.GET, base + "?q=" + "x".repeat(64)).with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, base + "?q=" + "x".repeat(65)).with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUERY_TOO_LONG"));
    }

    private User canonicalAdmin() {
        User value = users.findByUsernameKey("admin").orElseGet(User::new);
        value.setUsername("admin");
        value.setPassword(passwordEncoder.encode("strong-password-123"));
        value.setRole(Role.ADMIN);
        return users.saveAndFlush(value);
    }

    private void assertJsonInvalid(BodyCase item, String content) throws Exception {
        MockHttpServletRequestBuilder builder = request(item.method(), item.path()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON);
        if (item.authenticated()) builder.with(TestAuthentication.authenticated(player, securitySessions));
        if (content != null) builder.content(content);
        mvc.perform(builder)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("JSON_INVALID"));
    }

    private User user(String username, Role role) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(passwordEncoder.encode("strong-password-123"));
        value.setRole(role);
        return users.saveAndFlush(value);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static PathCase path(HttpMethod method, String path, String body) { return new PathCase(method, path, body); }
    private static BodyCase body(HttpMethod method, String path, boolean authenticated) { return new BodyCase(method, path, authenticated); }
    private record PathCase(HttpMethod method, String path, String body) {}
    private record BodyCase(HttpMethod method, String path, boolean authenticated) {}
}
