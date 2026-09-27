package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.Game;
import com.mafia.classicmafia.entity.GamePlayer;
import com.mafia.classicmafia.entity.GameRole;
import com.mafia.classicmafia.entity.GameWinner;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PlayersReadApiIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired GameRepository games;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousPlayersListIsPagedSearchableSortableAndHidesAdmin() throws Exception {
        User alpha = upsert("wp11006_list_alpha", Role.PLAYER);
        alpha.setRating(1200.0);
        alpha.setGamesPlayed(8);
        alpha.setWins(5);
        users.saveAndFlush(alpha);

        User beta = upsert("wp11006_list_beta", Role.MODERATOR);
        beta.setRating(1400.0);
        beta.setGamesPlayed(12);
        beta.setWins(9);
        users.saveAndFlush(beta);

        User admin = upsert("wp11006_list_admin", Role.ADMIN);
        admin.setRating(9999.0);
        users.saveAndFlush(admin);

        mvc.perform(get("/api/v1/players")
                .param("q", "wp11006_list_")
                .param("page", "0")
                .param("size", "25")
                .param("sort", "rating,desc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(25))
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.items[0].username").value("wp11006_list_beta"))
            .andExpect(jsonPath("$.items[0].role").doesNotExist())
            .andExpect(jsonPath("$.items[0].avatarUrl").value("/api/v1/players/" + beta.getId() + "/avatar?size=128"))
            .andExpect(jsonPath("$.items[1].username").value("wp11006_list_alpha"));
    }

    @Test
    void publicProfileExposesOnlyClientFieldsAndAdminIsNotDisclosed() throws Exception {
        User player = upsert("wp11006_profile", Role.PLAYER);
        player.setAboutMe("Публичное описание");
        player.setGamesPlayed(10);
        player.setWins(6);
        player.setLosses(4);
        player.setAverageScore(0.625);
        player.setRating(1337.0);
        player = users.saveAndFlush(player);

        mvc.perform(get("/api/v1/players/" + player.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(player.getId()))
            .andExpect(jsonPath("$.data.username").value("wp11006_profile"))
            .andExpect(jsonPath("$.data.aboutMe").value("Публичное описание"))
            .andExpect(jsonPath("$.data.role").doesNotExist())
            .andExpect(jsonPath("$.data.gamesPlayed").value(10))
            .andExpect(jsonPath("$.data.winRate").value(0.6))
            .andExpect(jsonPath("$.data.avatarUrl").value("/api/v1/players/" + player.getId() + "/avatar"))
            .andExpect(jsonPath("$.data.password").doesNotExist())
            .andExpect(jsonPath("$.data.credentialVersion").doesNotExist())
            .andExpect(jsonPath("$.data.registrationIpHash").doesNotExist());

        User admin = users.saveAndFlush(upsert("wp11006_hidden_admin", Role.ADMIN));
        mvc.perform(get("/api/v1/players/" + admin.getId()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void playerHistoryFiltersHiddenGamesForAnonymousAndAllowsAuthorizedViewer() throws Exception {
        User target = users.saveAndFlush(upsert("wp11006_history_target", Role.PLAYER));
        User viewer = users.saveAndFlush(upsert("wp11006_history_viewer", Role.PLAYER));

        Game publicGame = game("WP11-006 public game", LocalDate.of(2026, 9, 10), false, target);
        publicGame = games.saveAndFlush(publicGame);

        Game hiddenGame = game("WP11-006 hidden game", LocalDate.of(2026, 9, 11), true, target);
        hiddenGame.replaceHiddenAllowedUsers(List.of(viewer));
        hiddenGame = games.saveAndFlush(hiddenGame);

        mvc.perform(get("/api/v1/players/" + target.getId() + "/games"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].gameId").value(publicGame.getId()))
            .andExpect(jsonPath("$.items[0].name").value("WP11-006 public game"));

        mvc.perform(get("/api/v1/players/" + target.getId() + "/games")
                .with(user(viewer.getUsername()).roles("PLAYER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.items[0].gameId").value(hiddenGame.getId()))
            .andExpect(jsonPath("$.items[1].gameId").value(publicGame.getId()));

        mvc.perform(get("/api/v1/players/" + target.getId() + "/games")
                .with(user(viewer.getUsername()).roles("PLAYER"))
                .param("page", "0")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items[0].gameId").value(hiddenGame.getId()));

        mvc.perform(get("/api/v1/players/" + target.getId() + "/games")
                .with(user(viewer.getUsername()).roles("PLAYER"))
                .param("page", "1")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items[0].gameId").value(publicGame.getId()));
    }

    @Test
    void playerAvatarReturnsResponsiveCacheableMediaAndSupportsConditionalGet() throws Exception {
        User safe = upsert("wp11006_avatar", Role.PLAYER);
        byte[] png = png(512, 384);
        safe.setAvatarData(png);
        safe.setAvatarContentType("image/png");
        safe.setAvatarSafetyVersion(1);
        safe = users.saveAndFlush(safe);

        var full = mvc.perform(get("/api/v1/players/" + safe.getId() + "/avatar"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("public")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-cache")))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn();
        org.assertj.core.api.Assertions.assertThat(full.getResponse().getContentAsByteArray().length).isLessThan(png.length);
        BufferedImage fullImage = ImageIO.read(new ByteArrayInputStream(full.getResponse().getContentAsByteArray()));
        org.assertj.core.api.Assertions.assertThat(fullImage).isNotNull();
        org.assertj.core.api.Assertions.assertThat(fullImage.getWidth()).isEqualTo(512);
        org.assertj.core.api.Assertions.assertThat(fullImage.getHeight()).isEqualTo(384);

        String etag = full.getResponse().getHeader(HttpHeaders.ETAG);
        mvc.perform(get("/api/v1/players/" + safe.getId() + "/avatar")
                .header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string(HttpHeaders.ETAG, etag))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("public")))
            .andExpect(content().bytes(new byte[0]));

        var compact = mvc.perform(get("/api/v1/players/" + safe.getId() + "/avatar").param("size", "128"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn();
        BufferedImage compactImage = ImageIO.read(new ByteArrayInputStream(compact.getResponse().getContentAsByteArray()));
        org.assertj.core.api.Assertions.assertThat(compactImage).isNotNull();
        org.assertj.core.api.Assertions.assertThat(compactImage.getWidth()).isEqualTo(128);
        org.assertj.core.api.Assertions.assertThat(compactImage.getHeight()).isEqualTo(96);
        org.assertj.core.api.Assertions.assertThat(compact.getResponse().getContentAsByteArray().length).isLessThan(png.length);

        var profileSized = mvc.perform(get("/api/v1/players/" + safe.getId() + "/avatar").param("size", "256"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn();
        BufferedImage profileImage = ImageIO.read(new ByteArrayInputStream(profileSized.getResponse().getContentAsByteArray()));
        org.assertj.core.api.Assertions.assertThat(profileImage).isNotNull();
        org.assertj.core.api.Assertions.assertThat(profileImage.getWidth()).isEqualTo(256);
        org.assertj.core.api.Assertions.assertThat(profileImage.getHeight()).isEqualTo(192);

        mvc.perform(get("/api/v1/players/" + safe.getId() + "/avatar").param("size", "64"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AVATAR_SIZE_INVALID"));

        byte[] changed = png(512, 512);
        safe.setAvatarData(changed);
        users.saveAndFlush(safe);
        mvc.perform(get("/api/v1/players/" + safe.getId() + "/avatar")
                .header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo(etag))));

        User fallback = users.saveAndFlush(upsert("wp11006_default_avatar", Role.PLAYER));
        mvc.perform(get("/api/v1/players/" + fallback.getId() + "/avatar"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/css/default-avatar.svg"));

        User admin = users.saveAndFlush(upsert("wp11006_avatar_admin", Role.ADMIN));
        mvc.perform(get("/api/v1/players/" + admin.getId() + "/avatar"))
            .andExpect(status().isNotFound());
    }

    @Test
    void playersEndpointRejectsUnapprovedSortAndUnsafePagination() throws Exception {
        mvc.perform(get("/api/v1/players").param("sort", "password,asc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SORT_INVALID"));

        mvc.perform(get("/api/v1/players").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PAGE_INVALID"));

        mvc.perform(get("/api/v1/players").param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PAGE_SIZE_INVALID"));
    }


    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = 255;
                int red = x * 255 / Math.max(1, width - 1);
                int green = y * 255 / Math.max(1, height - 1);
                int blue = (x + y) & 255;
                image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
        return output.toByteArray();
    }

    private User upsert(String username, Role role) {
        User user = users.findByUsernameKey(User.canonicalUsername(username)).orElseGet(User::new);
        user.setUsername(username);
        user.setPassword("test-password-hash");
        user.setRole(role);
        return user;
    }

    private Game game(String name, LocalDate date, boolean hidden, User target) {
        Game game = new Game();
        game.setName(name);
        game.setGameDate(date);
        game.setHost("WP11-006 host");
        game.setWinner(GameWinner.CIVILIANS);
        game.setSummary("");
        game.setHiddenFromPublic(hidden);

        GamePlayer row = new GamePlayer();
        row.setUser(target);
        row.setSeatNumber(1);
        row.setDisplayName(target.getUsername());
        row.setRole(GameRole.CIVILIAN);
        row.setWon(true);
        row.setVictoryPoints(new BigDecimal("2.50"));
        row.setFirstKilledPoints(BigDecimal.ZERO);
        row.setBestMovePoints(BigDecimal.ZERO);
        row.setBonusPoints(new BigDecimal("0.50"));
        row.setPenaltyPoints(BigDecimal.ZERO);
        row.setTotalPoints(new BigDecimal("3.00"));
        game.addPlayer(row);
        return game;
    }
}
