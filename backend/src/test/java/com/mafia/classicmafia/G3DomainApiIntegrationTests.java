package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScopeOverrideDecision;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.NewsRepository;
import com.mafia.classicmafia.repository.ScheduleEntryRepository;
import com.mafia.classicmafia.repository.TournamentRepository;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class G3DomainApiIntegrationTests {
    private static final String PASSWORD = "strong-password-123";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired GameRepository games;
    @Autowired ScheduleEntryRepository schedule;
    @Autowired TournamentRepository tournaments;
    @Autowired NewsRepository news;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SecuritySessionService securitySessions;
    @Autowired ModeratorAdministrationService moderatorAdministration;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void gamesApiUsesLegacyScoringVideoValidationAndHiddenVisibility() throws Exception {
        User admin = createAdmin();
        User allowed = createUser("wp11011_allowed_" + suffix(), Role.PLAYER);
        String tournamentName = "WP11-011 tournament " + suffix();

        mvc.perform(post("/api/v1/tournaments")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tournamentJson(tournamentName, "")))
            .andExpect(status().isOk());
        Long tournamentId = tournaments.findAll().stream().filter(item -> item.getName().equals(tournamentName)).findFirst().orElseThrow().getId();

        String gameName = "WP11-011 hidden " + suffix();
        mvc.perform(post("/api/v1/games")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(gameJson(gameName, allowed.getUsername(), tournamentId, true, List.of(allowed.getUsername()), "https://example.com/video")))
            .andExpect(status().isOk());
        Long gameId = games.findAll().stream().filter(item -> item.getName().equals(gameName)).findFirst().orElseThrow().getId();

        mvc.perform(get("/api/v1/games/" + gameId))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/games/" + gameId).with(TestAuthentication.authenticated(allowed, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.players[0].displayName").value(allowed.getUsername()))
            .andExpect(jsonPath("$.data.players[0].won").value(true))
            .andExpect(jsonPath("$.data.videoLinks[0]").value("https://example.com/video"))
            .andExpect(jsonPath("$.data.visibilityUsernames").isEmpty());
        mvc.perform(get("/api/v1/games/" + gameId).with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.visibilityUsernames[0]").value(allowed.getUsername()));

        mvc.perform(post("/api/v1/games")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(gameJson("bad video " + suffix(), allowed.getUsername(), null, false, List.of(), "javascript:alert(1)")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("GAME_INVALID"));
    }

    @Test
    void gameStatsRecalculateOnlyAffectedRegisteredPlayersAcrossEditAndDelete() throws Exception {
        User admin = createAdmin();
        User first = createUser("stats_first_" + suffix(), Role.PLAYER);
        User second = createUser("stats_second_" + suffix(), Role.PLAYER);
        String gameName = "targeted stats " + suffix();

        mvc.perform(post("/api/v1/games")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(gameJson(gameName, first.getUsername(), null, false, List.of(), "https://example.com/stats")))
            .andExpect(status().isOk());
        Long gameId = games.findAll().stream().filter(item -> item.getName().equals(gameName)).findFirst().orElseThrow().getId();

        User firstAfterCreate = users.findById(first.getId()).orElseThrow();
        User secondAfterCreate = users.findById(second.getId()).orElseThrow();
        assertThat(firstAfterCreate.getGamesPlayed()).isEqualTo(1);
        assertThat(firstAfterCreate.getWins()).isEqualTo(1);
        assertThat(secondAfterCreate.getGamesPlayed()).isZero();

        mvc.perform(put("/api/v1/games/" + gameId)
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(gameJson(gameName, second.getUsername(), null, false, List.of(), "https://example.com/stats")))
            .andExpect(status().isOk());

        User firstAfterEdit = users.findById(first.getId()).orElseThrow();
        User secondAfterEdit = users.findById(second.getId()).orElseThrow();
        assertThat(firstAfterEdit.getGamesPlayed()).isZero();
        assertThat(firstAfterEdit.getWins()).isZero();
        assertThat(firstAfterEdit.getLosses()).isZero();
        assertThat(secondAfterEdit.getGamesPlayed()).isEqualTo(1);
        assertThat(secondAfterEdit.getWins()).isEqualTo(1);

        mvc.perform(delete("/api/v1/games/" + gameId)
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf()))
            .andExpect(status().isOk());

        User secondAfterDelete = users.findById(second.getId()).orElseThrow();
        assertThat(secondAfterDelete.getGamesPlayed()).isZero();
        assertThat(secondAfterDelete.getWins()).isZero();
        assertThat(secondAfterDelete.getLosses()).isZero();
        assertThat(secondAfterDelete.getAverageScore()).isZero();
    }

    @Test
    void scheduleApiPreservesGameEventFieldsAndParticipation() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11012_player_" + suffix(), Role.PLAYER);
        String gameTitle = "WP11-012 game " + suffix();
        String date = LocalDateTime.now().plusDays(3).withSecond(0).withNano(0).toString();

        mvc.perform(post("/api/v1/schedule")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryType\":\"GAME\",\"title\":\"" + gameTitle + "\",\"scheduledAt\":\"" + date + "\",\"host\":\"Host\",\"description\":\"Description\",\"rated\":true,\"mafiaFormat\":\"CITY\"}"))
            .andExpect(status().isOk());
        Long gameEntryId = schedule.findAll().stream().filter(item -> item.getTitle().equals(gameTitle)).findFirst().orElseThrow().getId();

        mvc.perform(get("/api/v1/schedule"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title == '" + gameTitle + "')].rated").value(hasItem(true)))
            .andExpect(jsonPath("$[?(@.title == '" + gameTitle + "')].mafiaFormat").value(hasItem("CITY")));

        mvc.perform(post("/api/v1/schedule/" + gameEntryId + "/participation")
                .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.participating").value(true));

        String eventTitle = "WP11-012 event " + suffix();
        mvc.perform(post("/api/v1/schedule")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryType\":\"EVENT\",\"title\":\"" + eventTitle + "\",\"scheduledAt\":\"" + date + "\",\"host\":\"Organizer\",\"description\":\"Large description\",\"rated\":true,\"mafiaFormat\":\"CITY\"}"))
            .andExpect(status().isOk());
        var event = schedule.findAll().stream().filter(item -> item.getTitle().equals(eventTitle)).findFirst().orElseThrow();
        assertThat(event.isRated()).isFalse();
        assertThat(event.getMafiaFormat().name()).isEqualTo("CLASSIC");
        mvc.perform(post("/api/v1/schedule/" + event.getId() + "/participation")
                .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_GAME"));
    }

    @Test
    void scheduleRangeReturnsOnlyRequestedWindowAndRejectsInvalidRanges() throws Exception {
        User admin = createAdmin();
        String suffix = suffix();
        String insideTitle = "WP11 schedule range inside " + suffix;
        String outsideTitle = "WP11 schedule range outside " + suffix;
        LocalDateTime from = LocalDateTime.now().plusDays(40).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime to = from.plusDays(7);
        LocalDateTime outside = from.plusDays(10);

        mvc.perform(post("/api/v1/schedule")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryType\":\"GAME\",\"title\":\"" + insideTitle + "\",\"scheduledAt\":\"" + from.plusDays(1) + "\",\"host\":\"Host\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/schedule")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryType\":\"GAME\",\"title\":\"" + outsideTitle + "\",\"scheduledAt\":\"" + outside + "\",\"host\":\"Host\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/schedule").param("from", from.toString()).param("to", to.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].title").value(hasItem(insideTitle)))
            .andExpect(jsonPath("$[*].title").value(not(hasItem(outsideTitle))));

        mvc.perform(get("/api/v1/schedule").param("from", from.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SCHEDULE_RANGE_INVALID"));

        mvc.perform(get("/api/v1/schedule")
                .param("from", from.toString())
                .param("to", from.plusDays(32).toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SCHEDULE_RANGE_INVALID"));
    }

    @Test
    void paidTournamentRequiresInvitationAndDoesNotExposeInvitationRowsPublicly() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11013_player_" + suffix(), Role.PLAYER);
        String name = "WP11-013 paid " + suffix();

        mvc.perform(post("/api/v1/tournaments")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tournamentJson(name, "1500")))
            .andExpect(status().isOk());
        Long id = tournaments.findAll().stream().filter(item -> item.getName().equals(name)).findFirst().orElseThrow().getId();

        mvc.perform(post("/api/v1/tournaments/" + id + "/participation")
                .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TOURNAMENT_PARTICIPATION_INVALID"));

        mvc.perform(post("/api/v1/tournaments/" + id + "/invitations")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + player.getUsername() + "\"}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/tournaments/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.invitationRows").isEmpty());
        mvc.perform(get("/api/v1/tournaments/" + id).with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.invitationRows[0].username").value(player.getUsername()));

        mvc.perform(post("/api/v1/tournaments/" + id + "/participation")
                .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.participating").value(true));
    }


    @Test
    void gameRejectsDuplicateSeatsAndDuplicatePlayerIdentity() throws Exception {
        User admin = createAdmin();
        User player = createUser("dup_game_" + suffix(), Role.PLAYER);
        String common = "\"gameDate\":\"" + LocalDate.now() + "\",\"host\":\"Host\",\"winner\":\"CIVILIANS\",\"summary\":\"\",\"videoLinks\":[],\"tournamentId\":null,\"hiddenFromPublic\":false,\"visibilityUsernames\":[]";
        String p1 = "{\"seatNumber\":1,\"username\":\"" + player.getUsername() + "\",\"role\":\"CIVILIAN\",\"firstKilled\":false,\"bestMove1\":null,\"bestMove2\":null,\"bestMove3\":null,\"bonusPoints\":\"0\",\"penaltyPoints\":\"0\"}";
        String sameSeatOther = "{\"seatNumber\":1,\"username\":\"guest_other\",\"role\":\"CIVILIAN\",\"firstKilled\":false,\"bestMove1\":null,\"bestMove2\":null,\"bestMove3\":null,\"bonusPoints\":\"0\",\"penaltyPoints\":\"0\"}";
        String samePlayerOtherSeat = "{\"seatNumber\":2,\"username\":\"" + player.getUsername() + "\",\"role\":\"CIVILIAN\",\"firstKilled\":false,\"bestMove1\":null,\"bestMove2\":null,\"bestMove3\":null,\"bonusPoints\":\"0\",\"penaltyPoints\":\"0\"}";

        mvc.perform(post("/api/v1/games").with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup-seat-" + suffix() + "\"," + common + ",\"players\":[" + p1 + "," + sameSeatOther + "]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GAME_INVALID"));

        mvc.perform(post("/api/v1/games").with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup-player-" + suffix() + "\"," + common + ",\"players\":[" + p1 + "," + samePlayerOtherSeat + "]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GAME_INVALID"));
    }

    @Test
    void repeatedParticipationTogglesNeverAccumulateDuplicates() throws Exception {
        User admin = createAdmin();
        User player = createUser("toggle_player_" + suffix(), Role.PLAYER);
        String when = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0).toString();
        String title = "toggle schedule " + suffix();
        mvc.perform(post("/api/v1/schedule").with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryType\":\"GAME\",\"title\":\"" + title + "\",\"scheduledAt\":\"" + when + "\",\"host\":\"Host\",\"description\":\"\",\"rated\":false,\"mafiaFormat\":\"CLASSIC\"}"))
            .andExpect(status().isOk());
        Long scheduleId = schedule.findAll().stream().filter(item -> item.getTitle().equals(title)).findFirst().orElseThrow().getId();
        for (boolean expected : new boolean[]{true, false, true}) {
            mvc.perform(post("/api/v1/schedule/" + scheduleId + "/participation").with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.participating").value(expected));
        }
        mvc.perform(get("/api/v1/schedule").with(TestAuthentication.authenticated(player, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + scheduleId + ")].participants.length()").value(hasItem(1)))
            .andExpect(jsonPath("$[?(@.id == " + scheduleId + ")].participating").value(hasItem(true)));

        String tournamentName = "toggle tournament " + suffix();
        mvc.perform(post("/api/v1/tournaments").with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(tournamentJson(tournamentName, "")))
            .andExpect(status().isOk());
        Long tournamentId = tournaments.findAll().stream().filter(item -> item.getName().equals(tournamentName)).findFirst().orElseThrow().getId();
        for (boolean expected : new boolean[]{true, false, true}) {
            mvc.perform(post("/api/v1/tournaments/" + tournamentId + "/participation").with(TestAuthentication.authenticated(player, securitySessions)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.participating").value(expected));
        }
        mvc.perform(get("/api/v1/tournaments/" + tournamentId).with(TestAuthentication.authenticated(player, securitySessions)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.participantCount").value(1)).andExpect(jsonPath("$.data.participating").value(true));
    }

    @Test
    void ratingProjectionKeepsPublicVisibilityRules() throws Exception {
        User admin = createAdmin();
        User player = createUser("rating_projection_" + suffix(), Role.PLAYER);
        String visibleName = "rating visible " + suffix();

        mvc.perform(post("/api/v1/games")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(gameJson(visibleName, player.getUsername(), null, false, List.of(), "https://example.com/rating-visible")))
            .andExpect(status().isOk());

        String tournamentName = "rating hidden tournament " + suffix();
        mvc.perform(post("/api/v1/tournaments")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tournamentJson(tournamentName, "")))
            .andExpect(status().isOk());
        Long tournamentId = tournaments.findAll().stream()
            .filter(item -> item.getName().equals(tournamentName))
            .findFirst().orElseThrow().getId();

        mvc.perform(post("/api/v1/games")
                .with(TestAuthentication.authenticated(admin, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(gameJson("rating hidden " + suffix(), player.getUsername(), tournamentId, true, List.of(player.getUsername()), "https://example.com/rating-hidden")))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/rating?sort=points&page=0&size=100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.userId == " + player.getId() + ")].games").value(hasItem(1)))
            .andExpect(jsonPath("$.items[?(@.userId == " + player.getId() + ")].wins").value(hasItem(1)));
    }

    @Test
    void ratingIsPublicAndUsesServerAggregate() throws Exception {
        mvc.perform(get("/api/v1/rating?sort=points&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void newsAndRulesKeepPublicReadAndScopedRawEditAccess() throws Exception {
        User player = createUser("wp11015_player_" + suffix(), Role.PLAYER);
        User moderator = createUser("wp11015_mod_" + suffix(), Role.MODERATOR);
        moderatorAdministration.setOverride(moderator.getId(), ModeratorScope.NEWS_MANAGE, ScopeOverrideDecision.ALLOW);
        moderator = users.findById(moderator.getId()).orElseThrow();
        String title = "WP11-015 news " + suffix();

        mvc.perform(get("/api/v1/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manageable").value(false))
            .andExpect(jsonPath("$.data.editableContent").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(post("/api/v1/news")
                .with(TestAuthentication.authenticated(player, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"no\",\"content\":\"forbidden\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/news")
                .with(TestAuthentication.authenticated(moderator, securitySessions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"Safe plain text\"}"))
            .andExpect(status().isOk());
        assertThat(news.findAllByOrderByCreatedAtDesc().stream().anyMatch(item -> item.getTitle().equals(title))).isTrue();

        mvc.perform(get("/api/v1/news"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title == '" + title + "')].content").value(hasItem("Safe plain text")));
    }

    private String gameJson(String name, String username, Long tournamentId, boolean hidden, List<String> visibility, String video) {
        String tournament = tournamentId == null ? "null" : tournamentId.toString();
        String visible = visibility.stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(","));
        return "{\"name\":\"" + name + "\",\"gameDate\":\"" + LocalDate.now() + "\",\"host\":\"Host\",\"winner\":\"CIVILIANS\",\"summary\":\"Summary\",\"videoLinks\":[\"" + video + "\"],\"tournamentId\":" + tournament + ",\"hiddenFromPublic\":" + hidden + ",\"visibilityUsernames\":[" + visible + "],\"players\":[{\"seatNumber\":1,\"username\":\"" + username + "\",\"role\":\"CIVILIAN\",\"firstKilled\":false,\"bestMove1\":null,\"bestMove2\":null,\"bestMove3\":null,\"bonusPoints\":\"0\",\"penaltyPoints\":\"0\"}]}";
    }

    private String tournamentJson(String name, String cost) {
        return "{\"name\":\"" + name + "\",\"dates\":[\"" + LocalDate.now().plusDays(5) + "\"],\"gameCount\":3,\"participationCost\":\"" + cost + "\",\"description\":\"Tournament description\"}";
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

    private UsernamePasswordAuthenticationToken auth(User user) {
        MafiaUserPrincipal principal = new MafiaUserPrincipal(user.getId(), user.getUsername(), user.getPassword(), user.getRole().name(), user.getCredentialVersion(), user.getAuthorizationVersion());
        return new UsernamePasswordAuthenticationToken(principal, principal.getPassword(), List.of(new SimpleGrantedAuthority("ROLE_" + principal.roleName())));
    }

    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 8); }
}
