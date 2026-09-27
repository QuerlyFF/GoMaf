package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.Game;
import com.mafia.classicmafia.entity.GameWinner;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.Tournament;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.TournamentRepository;
import com.mafia.classicmafia.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = "app.spa.index-resource=classpath:/g4-test/index.html")
class ProductionSpaIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired GameRepository games;
    @Autowired TournamentRepository tournaments;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void allowListedBrowserRoutesServeNoCacheSpaIndex() throws Exception {
        for (String path : new String[]{"/", "/news", "/rules", "/players", "/games", "/schedule", "/tournaments", "/rating", "/login", "/register"}) {
            mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("G4-SPA-INDEX")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
        }
    }

    @Test
    void fingerprintedAssetsUseImmutableCaching() throws Exception {
        mvc.perform(get("/assets/g4-test.js"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
            .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    @Test
    void fingerprintedFontsRemainPublicAndImmutable() throws Exception {
        mvc.perform(get("/fonts/g4-test.txt"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
            .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    @Test
    void faviconFallbacksRemainPublic() throws Exception {
        mvc.perform(get("/favicon.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
        mvc.perform(get("/favicon.ico"))
            .andExpect(status().isOk());
    }

    @Test
    void cspAllowsOnlySelfHostedCompiledScripts() throws Exception {
        mvc.perform(get("/news"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
            .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
            .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-eval"))));
    }

    @Test
    void playerProfilePreloadsOneResponsiveLcpBannerCandidateSet() throws Exception {
        User player = users.findByUsernameKey(User.canonicalUsername("g4_preload_player")).orElseGet(User::new);
        player.setUsername("g4_preload_player");
        player.setPassword(passwordEncoder.encode("strong-password"));
        player.setRole(Role.PLAYER);
        player = users.saveAndFlush(player);
        String playerPath = "/players/" + player.getId();

        mvc.perform(get(playerPath))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("rel=\"preload\" as=\"image\" imagesrcset=\"/api/v1/players/" + player.getId() + "/banner?width=640 640w, /api/v1/players/" + player.getId() + "/banner?width=768 768w, /api/v1/players/" + player.getId() + "/banner?width=960 960w, /api/v1/players/" + player.getId() + "/banner?width=1280 1280w\"")))
            .andExpect(content().string(containsString("imagesizes=\"(max-width: 640px) calc(100vw - 26px), (max-width: 720px) calc(100vw - 42px), (max-width: 1024px) 960px, 1280px\" fetchpriority=\"high\"")))
            .andExpect(content().string(containsString("rel=\"canonical\" href=\"https://gomaf.online" + playerPath + "\"")))
            .andExpect(content().string(not(containsString("media=\"(min-width: 1025px)\""))));

        mvc.perform(get("/players"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("rel=\"preload\" as=\"image\""))));
    }

    @Test
    void publicRoutesExposeRouteSpecificSeoMetadataAndPrivateRoutesAreNoindex() throws Exception {
        mvc.perform(get("/players"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<title>Игроки — GoMaf</title>")))
            .andExpect(content().string(containsString("rel=\"canonical\" href=\"https://gomaf.online/players\"")))
            .andExpect(content().string(containsString("name=\"robots\" content=\"index,follow\"")));

        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<title>Вход — GoMaf</title>")))
            .andExpect(content().string(containsString("name=\"robots\" content=\"noindex,nofollow\"")))
            .andExpect(content().string(not(containsString("rel=\"canonical\""))));
    }

    @Test
    void missingDynamicPublicEntitiesReturnRealNoindex404() throws Exception {
        for (String path : new String[]{
            "/players/999999999",
            "/games/999999999",
            "/tournaments/999999999",
            "/players/999999999999999999999999999999"
        }) {
            mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
                .andExpect(content().string(containsString("name=\"robots\" content=\"noindex,nofollow\"")))
                .andExpect(content().string(not(containsString("rel=\"canonical\""))))
                .andExpect(content().string(containsString("<h1>404</h1>")));
        }
    }

    @Test
    void hiddenGameShellMatchesApiVisibilityAndRemainsNoindex() throws Exception {
        User viewer = users.findByUsernameKey(User.canonicalUsername("g4_hidden_viewer")).orElseGet(User::new);
        viewer.setUsername("g4_hidden_viewer");
        viewer.setPassword(passwordEncoder.encode("strong-password"));
        viewer.setRole(Role.PLAYER);
        viewer = users.saveAndFlush(viewer);
        Tournament tournament = new Tournament();
        tournament.setName("G4 hidden tournament");
        tournament.setGameCount(1);
        tournament.setDescription("");
        tournament = tournaments.saveAndFlush(tournament);

        Game game = new Game();
        game.setName("G4 hidden game");
        game.setGameDate(LocalDate.of(2026, 9, 22));
        game.setHost("G4");
        game.setWinner(GameWinner.CIVILIANS);
        game.setHiddenFromPublic(true);
        game.setTournament(tournament);
        game.replaceHiddenAllowedUsers(List.of(viewer));
        game = games.saveAndFlush(game);
        String path = "/games/" + game.getId();

        mvc.perform(get(path))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Robots-Tag", containsString("noindex")));

        mvc.perform(get(path).with(user(viewer.getUsername())))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
            .andExpect(content().string(containsString("name=\"robots\" content=\"noindex,nofollow\"")))
            .andExpect(content().string(not(containsString("rel=\"canonical\""))));
    }

    @Test
    void robotsAndSitemapArePublicAndContainPublicDiscoveryUrls() throws Exception {
        User player = users.findByUsernameKey(User.canonicalUsername("g4_sitemap_player")).orElseGet(User::new);
        player.setUsername("g4_sitemap_player");
        player.setPassword(passwordEncoder.encode("strong-password"));
        player.setRole(Role.PLAYER);
        player = users.saveAndFlush(player);

        mvc.perform(get("/robots.txt"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("Sitemap: https://gomaf.online/sitemap.xml")))
            .andExpect(content().string(containsString("Disallow: /admin/")))
            .andExpect(content().string(containsString("Disallow: /profile")))
            .andExpect(content().string(not(containsString("Disallow: /login"))))
            .andExpect(content().string(not(containsString("Disallow: /register"))))
            .andExpect(content().string(not(containsString("Disallow: /games/new"))));

        mvc.perform(get("/sitemap.xml"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(containsString("<loc>https://gomaf.online/players</loc>")))
            .andExpect(content().string(containsString("<loc>https://gomaf.online/players/" + player.getId() + "</loc>")))
            .andExpect(content().string(not(containsString("<loc>https://gomaf.online/login</loc>"))));
    }

    @Test
    void unknownBrowserRouteReturnsRealNoindex404InsteadOfLogin() throws Exception {
        mvc.perform(get("/definitely-not-real-page"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
            .andExpect(header().doesNotExist("Location"))
            .andExpect(content().string(containsString("<meta name=\"robots\" content=\"noindex,nofollow\"")))
            .andExpect(content().string(containsString("<h1>404</h1>")));

        mvc.perform(get("/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("<meta name=\"robots\" content=\"noindex,nofollow\"")))
            .andExpect(content().string(containsString("<h1>404</h1>")));
    }

    @Test
    void authenticatedUnknownApiPathReturnsJson404AndNeverSpa() throws Exception {
        User account = users.findByUsernameKey(User.canonicalUsername("g4_api_probe")).orElseGet(User::new);
        account.setUsername("g4_api_probe");
        account.setPassword(passwordEncoder.encode("strong-password"));
        account.setRole(Role.PLAYER);
        users.saveAndFlush(account);

        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/login").session(session)).andExpect(status().isOk());
        String answer = (String) session.getAttribute("captcha:login:code");
        mvc.perform(post("/api/v1/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"g4_api_probe\",\"password\":\"strong-password\",\"captcha\":\"" + answer + "\"}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/definitely-not-a-route").session(session))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("API_NOT_FOUND"))
            .andExpect(content().string(not(containsString("G4-SPA-INDEX"))));
    }

    @Test
    void healthAndForbiddenRemainIndependentOfRetiredLegacyMvc() throws Exception {
        mvc.perform(get("/healthz"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get("/forbidden"))
            .andExpect(status().isForbidden());
    }
}
