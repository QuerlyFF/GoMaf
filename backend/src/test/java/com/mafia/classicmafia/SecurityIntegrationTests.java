package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class SecurityIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void publicSpaCaptchaAndHealthUseHardenedHeaders() throws Exception {
        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
            .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
            .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-eval"))))
            .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(get("/captcha/login")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void apiLoginRemainsCsrfProtected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"123456\",\"captcha\":\"ABCDE\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void apiLoginUsesCaptchaAndServerSession() throws Exception {
        User account = users.findByUsernameKey(User.canonicalUsername("retire_login_user")).orElseGet(User::new);
        account.setUsername("retire_login_user");
        account.setPassword(passwordEncoder.encode("strong-password"));
        account.setRole(Role.PLAYER);
        users.saveAndFlush(account);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/login").session(session)).andExpect(status().isOk());
        String answer = (String) session.getAttribute("captcha:login:code");
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"retire_login_user\",\"password\":\"strong-password\",\"captcha\":\"" + answer + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true));
    }

    @Test
    void protectedApiUsesJson401AndScopesStayServerSide() throws Exception {
        mvc.perform(get("/api/v1/admin/moderators"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(put("/api/v1/rules").with(user("ordinary").roles("PLAYER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"X\",\"content\":\"Раздел 1. X\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void retiredBrowserMutationRoutesAreNotMapped() throws Exception {
        mvc.perform(post("/rules").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().is4xxClientError());
        mvc.perform(post("/profile/avatar").with(user("player").roles("PLAYER")).with(csrf()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void legacyAvatarCompatibilityEndpointStillFailsSafe() throws Exception {
        User account = new User();
        account.setUsername("retire_old_avatar");
        account.setPassword(passwordEncoder.encode("strong-password"));
        account.setRole(Role.PLAYER);
        account.setAvatarData("unsafe".getBytes());
        account.setAvatarContentType("image/png");
        account.setAvatarSafetyVersion(null);
        account = users.saveAndFlush(account);
        mvc.perform(get("/avatar/" + account.getId()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/css/default-avatar.svg"));
    }

    @Test
    void legacyAvatarCompatibilityEndpointUsesValidatorCachingForSafeMedia() throws Exception {
        User account = new User();
        account.setUsername("retire_cached_avatar");
        account.setPassword(passwordEncoder.encode("strong-password"));
        account.setRole(Role.PLAYER);
        account.setAvatarData("safe-avatar".getBytes());
        account.setAvatarContentType("image/png");
        account.setAvatarSafetyVersion(1);
        account = users.saveAndFlush(account);

        var first = mvc.perform(get("/avatar/" + account.getId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-cache")))
            .andExpect(header().string("Cache-Control", containsString("public")))
            .andExpect(header().exists("ETag"))
            .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertNotNull(etag);

        mvc.perform(get("/avatar/" + account.getId()).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(content().bytes(new byte[0]));
    }
}
