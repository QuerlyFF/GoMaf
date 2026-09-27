package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.PersistentLoginTokenRepository;
import com.mafia.classicmafia.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PersistentLoginIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PersistentLoginTokenRepository tokens;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void loginIssuesHttpOnlyPersistentCookieAndStoresOnlyValidatorHash() throws Exception {
        Login login = loginPlayer("remember_issue");
        String cookie = rememberCookie(login.result());
        String[] parts = cookie.split("\\.", 2);
        assertThat(parts).hasSize(2);

        var row = tokens.findById(parts[0]).orElseThrow();
        assertThat(row.getValidatorHash()).isEqualTo(sha256(parts[1]));
        assertThat(row.getValidatorHash()).doesNotContain(parts[1]);
        assertThat(setCookieHeaders(login.result())).anySatisfy(value -> {
            assertThat(value).contains("GOMAF_REMEMBER=");
            assertThat(value).contains("HttpOnly");
            assertThat(value).contains("SameSite=Lax");
            assertThat(value).contains("Max-Age=7776000");
        });
    }

    @Test
    void persistentCookieRestoresServerSessionAndRotatesToken() throws Exception {
        Login login = loginPlayer("remember_restore");
        String original = rememberCookie(login.result());
        String originalSelector = original.split("\\.", 2)[0];

        MvcResult restored = mvc.perform(get("/api/v1/session")
                .cookie(new Cookie("GOMAF_REMEMBER", original)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andExpect(jsonPath("$.data.username").value(login.user().getUsername()))
            .andReturn();

        String rotated = rememberCookie(restored);
        assertThat(rotated).isNotEqualTo(original);
        assertThat(tokens.existsById(originalSelector)).isFalse();
        assertThat(tokens.existsById(rotated.split("\\.", 2)[0])).isTrue();
        assertThat(restored.getRequest().getSession(false)).isNotNull();
    }

    @Test
    void credentialVersionChangeRevokesPersistentLoginFailClosed() throws Exception {
        Login login = loginPlayer("remember_stale");
        String original = rememberCookie(login.result());

        User changed = users.findById(login.user().getId()).orElseThrow();
        changed.incrementCredentialVersion();
        users.saveAndFlush(changed);

        MvcResult result = mvc.perform(get("/api/v1/session")
                .cookie(new Cookie("GOMAF_REMEMBER", original)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(false))
            .andReturn();

        assertThat(tokens.findAllByUserIdOrderByCreatedAtAsc(changed.getId())).isEmpty();
        assertThat(setCookieHeaders(result)).anySatisfy(value -> {
            assertThat(value).startsWith("GOMAF_REMEMBER=");
            assertThat(value).contains("Max-Age=0");
        });
    }

    @Test
    void logoutDeletesPersistentTokenAndClearsCookie() throws Exception {
        Login login = loginPlayer("remember_logout");
        String remember = rememberCookie(login.result());
        String selector = remember.split("\\.", 2)[0];

        MvcResult logout = mvc.perform(post("/api/v1/auth/logout")
                .session(login.session())
                .cookie(new Cookie("GOMAF_REMEMBER", remember))
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andReturn();

        assertThat(tokens.existsById(selector)).isFalse();
        assertThat(setCookieHeaders(logout)).anySatisfy(value -> {
            assertThat(value).startsWith("GOMAF_REMEMBER=");
            assertThat(value).contains("Max-Age=0");
        });
    }

    private Login loginPlayer(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("secret6"));
        user.setRole(Role.PLAYER);
        user = users.saveAndFlush(user);

        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/login").session(session)).andExpect(status().isOk());
        String captcha = (String) session.getAttribute("captcha:login:code");
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"secret6\",\"captcha\":\"" + captcha + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andReturn();
        return new Login(user, session, result);
    }

    private String rememberCookie(MvcResult result) {
        return setCookieHeaders(result).stream()
            .filter(value -> value.startsWith("GOMAF_REMEMBER="))
            .map(value -> value.substring("GOMAF_REMEMBER=".length(), value.indexOf(';')))
            .findFirst()
            .orElseThrow(() -> new AssertionError("GOMAF_REMEMBER cookie missing"));
    }

    private List<String> setCookieHeaders(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie");
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest);
    }

    private record Login(User user, MockHttpSession session, MvcResult result) { }
}
