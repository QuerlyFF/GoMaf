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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ProfileApiIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired GameRepository games;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void profileRequiresAuthenticationAndReturnsOnlySelfFacingFields() throws Exception {
        mvc.perform(get("/api/v1/profile"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        User player = player("wp11010_self_" + suffix());
        player.setAboutMe("Мой профиль");
        player.setGamesPlayed(4);
        player.setWins(3);
        player.setLosses(1);
        player.setAverageScore(0.75);
        player.setRating(1234.5);
        player = users.saveAndFlush(player);

        mvc.perform(get("/api/v1/profile").with(user(player.getUsername()).roles("PLAYER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(player.getId()))
            .andExpect(jsonPath("$.data.username").value(player.getUsername()))
            .andExpect(jsonPath("$.data.role").value("PLAYER"))
            .andExpect(jsonPath("$.data.aboutMe").value("Мой профиль"))
            .andExpect(jsonPath("$.data.winRate").value(0.75))
            .andExpect(jsonPath("$.data.avatarUrl").value("/api/v1/profile/avatar"))
            .andExpect(jsonPath("$.data.bannerUrl").value("/api/v1/players/" + player.getId() + "/banner"))
            .andExpect(jsonPath("$.data.password").doesNotExist())
            .andExpect(jsonPath("$.data.credentialVersion").doesNotExist())
            .andExpect(jsonPath("$.data.registrationIpHash").doesNotExist());
    }

    @Test
    void aboutUpdateRequiresCsrfTrimsTextAndRejectsOversize() throws Exception {
        User player = users.saveAndFlush(player("wp11010_about_" + suffix()));
        String path = "/api/v1/profile/about";

        mvc.perform(patch(path)
                .with(user(player.getUsername()).roles("PLAYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aboutMe\":\"без csrf\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(patch(path)
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aboutMe\":\"  Новое описание  \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message").value("Текст профиля сохранён."));

        User updated = users.findById(player.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.getAboutMe()).isEqualTo("Новое описание");

        String tooLong = "x".repeat(501);
        mvc.perform(patch(path)
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aboutMe\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ABOUT_TOO_LONG"));
    }

    @Test
    void avatarUploadSanitizesToPngAndResetClearsIt() throws Exception {
        User player = users.saveAndFlush(player("wp11010_avatar_" + suffix()));
        MockMultipartFile avatar = new MockMultipartFile("avatar", "photo.jpg", "image/jpeg", jpegBytes());
        var upload = multipart("/api/v1/profile/avatar")
            .file(avatar)
            .with(user(player.getUsername()).roles("PLAYER"))
            .with(csrf())
            .with(request -> { request.setRemoteAddr("2001:db8:110:10::1"); return request; });

        mvc.perform(upload)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message").value("Аватар обновлён и безопасно обработан."));

        User stored = users.findById(player.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getAvatarContentType()).isEqualTo("image/png");
        org.assertj.core.api.Assertions.assertThat(stored.getAvatarSafetyVersion()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(stored.getAvatarData()).isNotEmpty();

        mvc.perform(get("/api/v1/profile/avatar").with(user(player.getUsername()).roles("PLAYER")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));

        mvc.perform(delete("/api/v1/profile/avatar")
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message").value("Аватар сброшен."));

        User reset = users.findById(player.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reset.getAvatarData()).isNull();
        org.assertj.core.api.Assertions.assertThat(reset.getAvatarContentType()).isNull();
        org.assertj.core.api.Assertions.assertThat(reset.getAvatarSafetyVersion()).isNull();
    }

    @Test
    void bannerUploadAcceptsGifIsPublicAndResetReturnsDefault() throws Exception {
        User player = users.saveAndFlush(player("wp11010_banner_" + suffix()));
        byte[] gif = gifBytes();
        MockMultipartFile banner = new MockMultipartFile("banner", "banner.gif", "image/gif", gif);

        mvc.perform(multipart("/api/v1/profile/banner")
                .file(banner)
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf())
                .with(request -> { request.setRemoteAddr("2001:db8:110:13::1"); return request; }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message").value("Баннер профиля обновлён."));

        User stored = users.findById(player.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getProfileBannerContentType()).isEqualTo("image/gif");
        org.assertj.core.api.Assertions.assertThat(stored.getProfileBannerSafetyVersion()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(stored.getProfileBannerData()).isEqualTo(gif);

        var publicBanner = mvc.perform(get("/api/v1/players/" + player.getId() + "/banner"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_GIF))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("public")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-cache")))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(content().bytes(gif))
            .andReturn();

        String bannerEtag = publicBanner.getResponse().getHeader(HttpHeaders.ETAG);
        mvc.perform(get("/api/v1/players/" + player.getId() + "/banner")
                .header(HttpHeaders.IF_NONE_MATCH, bannerEtag))
            .andExpect(status().isNotModified())
            .andExpect(header().string(HttpHeaders.ETAG, bannerEtag))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("public")))
            .andExpect(content().bytes(new byte[0]));

        mvc.perform(get("/api/v1/players/" + player.getId() + "/banner").param("width", "640"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_GIF))
            .andExpect(content().bytes(gif));

        mvc.perform(get("/api/v1/players/" + player.getId() + "/banner").param("width", "777"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BANNER_WIDTH_INVALID"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("768")));

        mvc.perform(delete("/api/v1/profile/banner")
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.message").value("Баннер профиля сброшен."));

        mvc.perform(get("/api/v1/players/" + player.getId() + "/banner"))
            .andExpect(status().isFound())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/css/default-profile-banner.svg"));
    }

    @Test
    void publicStaticBannerVariantIsResizedAndConditionallyCached() throws Exception {
        User player = player("wp11010_staticbanner_" + suffix());
        byte[] png = pngBytes(1920, 768);
        player.setProfileBannerData(png);
        player.setProfileBannerContentType("image/png");
        player.setProfileBannerSafetyVersion(1);
        player = users.saveAndFlush(player);

        var resized = mvc.perform(get("/api/v1/players/" + player.getId() + "/banner").param("width", "768"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn();
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(resized.getResponse().getContentAsByteArray()));
        org.assertj.core.api.Assertions.assertThat(image).isNotNull();
        org.assertj.core.api.Assertions.assertThat(image.getWidth()).isEqualTo(768);
        org.assertj.core.api.Assertions.assertThat(image.getHeight()).isEqualTo(307);

        String etag = resized.getResponse().getHeader(HttpHeaders.ETAG);
        mvc.perform(get("/api/v1/players/" + player.getId() + "/banner")
                .param("width", "768")
                .header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void bannerUploadRejectsNonImage() throws Exception {
        User player = users.saveAndFlush(player("wp11010_badbanner_" + suffix()));
        MockMultipartFile banner = new MockMultipartFile("banner", "fake.gif", "image/gif", "not-an-image".getBytes());

        mvc.perform(multipart("/api/v1/profile/banner")
                .file(banner)
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf())
                .with(request -> { request.setRemoteAddr("2001:db8:110:14::1"); return request; }))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BANNER_INVALID"));
    }

    @Test
    void avatarUploadRejectsNonImage() throws Exception {
        User player = users.saveAndFlush(player("wp11010_badavatar_" + suffix()));
        MockMultipartFile avatar = new MockMultipartFile("avatar", "fake.jpg", "image/jpeg", "not-an-image".getBytes());

        mvc.perform(multipart("/api/v1/profile/avatar")
                .file(avatar)
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf())
                .with(request -> { request.setRemoteAddr("2001:db8:110:11::1"); return request; }))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AVATAR_INVALID"));
    }

    @Test
    void corruptAvatarReturnsFixedPublicErrorWithoutDecoderDetails() throws Exception {
        User player = users.saveAndFlush(player("wp11010_corruptavatar_" + suffix()));
        byte[] corrupt = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52};
        MockMultipartFile avatar = new MockMultipartFile("avatar", "broken.png", "image/png", corrupt);

        var result = mvc.perform(multipart("/api/v1/profile/avatar")
                .file(avatar)
                .with(user(player.getUsername()).roles("PLAYER"))
                .with(csrf())
                .with(request -> { request.setRemoteAddr("2001:db8:110:12::1"); return request; }))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AVATAR_INVALID"))
            .andExpect(jsonPath("$.message").value("Файл не соответствует требованиям к аватару."))
            .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .doesNotContain("IIOException", "ImageIO", "IHDR", "decoder");
    }

    @Test
    void selfHistoryUsesSameHiddenGameVisibilityPolicy() throws Exception {
        User player = users.saveAndFlush(player("wp11010_history_" + suffix()));
        Game publicGame = games.saveAndFlush(game("WP11-010 public", false, player));
        Game hiddenDenied = games.saveAndFlush(game("WP11-010 hidden denied", true, player));
        Game hiddenAllowed = game("WP11-010 hidden allowed", true, player);
        hiddenAllowed.replaceHiddenAllowedUsers(List.of(player));
        hiddenAllowed = games.saveAndFlush(hiddenAllowed);

        mvc.perform(get("/api/v1/profile/games")
                .with(user(player.getUsername()).roles("PLAYER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.items[0].gameId").value(hiddenAllowed.getId()))
            .andExpect(jsonPath("$.items[1].gameId").value(publicGame.getId()));

        mvc.perform(get("/api/v1/profile/games")
                .with(user(player.getUsername()).roles("PLAYER"))
                .param("page", "1")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items[0].gameId").value(publicGame.getId()));

        org.assertj.core.api.Assertions.assertThat(hiddenDenied.getId()).isNotNull();
    }

    private User player(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("profile-pass"));
        user.setRole(Role.PLAYER);
        return user;
    }

    private Game game(String name, boolean hidden, User player) {
        Game game = new Game();
        game.setName(name);
        game.setGameDate(LocalDate.of(2026, 9, hidden ? 12 : 11));
        game.setHost("WP11-010 host");
        game.setWinner(GameWinner.CIVILIANS);
        game.setSummary("");
        game.setHiddenFromPublic(hidden);
        GamePlayer row = new GamePlayer();
        row.setUser(player);
        row.setSeatNumber(1);
        row.setDisplayName(player.getUsername());
        row.setRole(GameRole.CIVILIAN);
        row.setWon(true);
        row.setVictoryPoints(new BigDecimal("2.50"));
        row.setFirstKilledPoints(BigDecimal.ZERO);
        row.setBestMovePoints(BigDecimal.ZERO);
        row.setBonusPoints(BigDecimal.ZERO);
        row.setPenaltyPoints(BigDecimal.ZERO);
        row.setTotalPoints(new BigDecimal("2.50"));
        game.addPlayer(row);
        return game;
    }


    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = x * 255 / Math.max(1, width - 1);
                int green = y * 255 / Math.max(1, height - 1);
                int blue = (x + y) & 255;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private byte[] jpegBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00ff00);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpeg", output);
            return output.toByteArray();
        }
    }

    private byte[] gifBytes() throws Exception {
        BufferedImage image = new BufferedImage(24, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xd2ad65);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "gif", output);
            return output.toByteArray();
        }
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
