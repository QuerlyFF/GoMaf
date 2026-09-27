package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.News;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.NewsRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class ApiContractBoundaryIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired NewsRepository news;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SecuritySessionService securitySessions;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void malformedAndWrongTypeInputsUseStable4xxEnvelopes() throws Exception {
        mvc.perform(get("/api/v1/players?page=abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PARAMETER_TYPE_INVALID"));

        mvc.perform(get("/api/v1/players/not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PARAMETER_TYPE_INVALID"));

        mvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("JSON_INVALID"));

        mvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.TEXT_PLAIN)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_SUPPORTED"));
    }

    @Test
    void playerListPaginationAndSearchBoundariesAreStable() throws Exception {
        mvc.perform(get("/api/v1/players?size=1&page=0&sort=rating,desc"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/players?size=100&page=0&sort=username,asc"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/players?size=0"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_SIZE_INVALID"));
        mvc.perform(get("/api/v1/players?size=101"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_SIZE_INVALID"));
        mvc.perform(get("/api/v1/players?page=-1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_INVALID"));
        mvc.perform(get("/api/v1/players?sort=unknown"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SORT_INVALID"));
        mvc.perform(get("/api/v1/players?q=" + "x".repeat(65)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUERY_TOO_LONG"));
        mvc.perform(get("/api/v1/players/9223372036854775807"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void protectedApiRoutesRejectAnonymousRequests() throws Exception {
        List<Route> routes = List.of(
            new Route(HttpMethod.POST, "/api/v1/auth/logout"),
            new Route(HttpMethod.GET, "/api/v1/profile"),
            new Route(HttpMethod.GET, "/api/v1/profile/games"),
            new Route(HttpMethod.GET, "/api/v1/profile/avatar"),
            new Route(HttpMethod.PATCH, "/api/v1/profile/about"),
            new Route(HttpMethod.POST, "/api/v1/profile/avatar"),
            new Route(HttpMethod.DELETE, "/api/v1/profile/avatar"),
            new Route(HttpMethod.POST, "/api/v1/profile/banner"),
            new Route(HttpMethod.DELETE, "/api/v1/profile/banner"),
            new Route(HttpMethod.POST, "/api/v1/games"),
            new Route(HttpMethod.PUT, "/api/v1/games/999999"),
            new Route(HttpMethod.DELETE, "/api/v1/games/999999"),
            new Route(HttpMethod.POST, "/api/v1/schedule"),
            new Route(HttpMethod.PUT, "/api/v1/schedule/999999"),
            new Route(HttpMethod.DELETE, "/api/v1/schedule/999999"),
            new Route(HttpMethod.POST, "/api/v1/schedule/999999/participation"),
            new Route(HttpMethod.POST, "/api/v1/tournaments"),
            new Route(HttpMethod.PUT, "/api/v1/tournaments/999999"),
            new Route(HttpMethod.DELETE, "/api/v1/tournaments/999999"),
            new Route(HttpMethod.POST, "/api/v1/tournaments/999999/invitations"),
            new Route(HttpMethod.DELETE, "/api/v1/tournaments/999999/invitations/999999"),
            new Route(HttpMethod.POST, "/api/v1/tournaments/999999/participation"),
            new Route(HttpMethod.PUT, "/api/v1/rules"),
            new Route(HttpMethod.POST, "/api/v1/news"),
            new Route(HttpMethod.PUT, "/api/v1/news/999999"),
            new Route(HttpMethod.DELETE, "/api/v1/news/999999"),
            new Route(HttpMethod.GET, "/api/v1/admin/moderators"),
            new Route(HttpMethod.GET, "/api/v1/admin/moderators/defaults"),
            new Route(HttpMethod.GET, "/api/v1/admin/moderators/eligible"),
            new Route(HttpMethod.PUT, "/api/v1/admin/moderators/defaults"),
            new Route(HttpMethod.POST, "/api/v1/admin/moderators/999999"),
            new Route(HttpMethod.DELETE, "/api/v1/admin/moderators/999999"),
            new Route(HttpMethod.PUT, "/api/v1/admin/moderators/999999/scopes"),
            new Route(HttpMethod.GET, "/api/v1/players/999999/administration"),
            new Route(HttpMethod.PATCH, "/api/v1/players/999999/username"),
            new Route(HttpMethod.PATCH, "/api/v1/players/999999/rating"),
            new Route(HttpMethod.PATCH, "/api/v1/players/999999/profile"),
            new Route(HttpMethod.PUT, "/api/v1/players/999999/password"),
            new Route(HttpMethod.DELETE, "/api/v1/players/999999/avatar"),
            new Route(HttpMethod.DELETE, "/api/v1/players/999999")
        );
        for (Route route : routes) {
            MockHttpServletRequestBuilder builder = request(route.method(), route.path()).with(csrf());
            if (route.method() != HttpMethod.GET) {
                builder.contentType(MediaType.APPLICATION_JSON).content("{}");
            }
            mvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test
    void previouslyUncoveredEndpointsHaveDirectIntegrationCoverage() throws Exception {
        User admin = user("admin", Role.ADMIN);
        User player = user("contract_player_" + suffix(), Role.PLAYER);

        mvc.perform(get("/api/v1/admin/moderators/eligible")
                .with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());

        mvc.perform(post("/api/v1/auth/logout")
                .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
            .andExpect(status().isNoContent());

        News item = new News();
        item.setTitle("contract-news");
        item.setContent("before");
        item = news.saveAndFlush(item);

        mvc.perform(put("/api/v1/news/" + item.getId())
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"updated\",\"content\":\"after\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(item.getId()));

        mvc.perform(delete("/api/v1/news/" + item.getId())
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(item.getId()));
    }

    @Test
    void authenticatedUnknownApiRouteUsesStable404Envelope() throws Exception {
        User player = user("contract_unknown_" + suffix(), Role.PLAYER);
        mvc.perform(get("/api/v1/does-not-exist")
                .with(TestAuthentication.authenticated(player, securitySessions)))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }
    private User user(String username, Role role) {
        User user = users.findByUsernameKey(User.canonicalUsername(username)).orElseGet(User::new);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("strong-password-123"));
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record Route(HttpMethod method, String path) {}
}
