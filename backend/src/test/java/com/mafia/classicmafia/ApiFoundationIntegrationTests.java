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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
class ApiFoundationIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousSessionEndpointUsesStableEnvelopeAndRequestId() throws Exception {
        mvc.perform(get("/api/v1/session"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.data.authenticated").value(false))
            .andExpect(jsonPath("$.data.moderatorPermissions").isArray());
    }

    @Test
    void csrfEndpointExposesSpaTokenEnvelope() throws Exception {
        mvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.headerName").isNotEmpty())
            .andExpect(jsonPath("$.data.parameterName").isNotEmpty())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void apiMutationWithoutCsrfReturnsJson403InsteadOfMvcRedirect() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"someone\",\"password\":\"strong-password\",\"captcha\":\"ABCDE\"}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unknownProtectedApiPathReturnsJson401ForAnonymousRequest() throws Exception {
        mvc.perform(get("/api/v1/protected-probe"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void apiLoginUsesCaptchaAndCreatesAuthenticatedServerSession() throws Exception {
        User account = users.findByUsernameKey(User.canonicalUsername("api_login_user")).orElseGet(User::new);
        account.setUsername("api_login_user");
        account.setPassword(passwordEncoder.encode("strong-password"));
        account.setRole(Role.PLAYER);
        users.saveAndFlush(account);

        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/login").session(session)).andExpect(status().isOk());
        String answer = (String) session.getAttribute("captcha:login:code");
        String originalSessionId = session.getId();

        mvc.perform(post("/api/v1/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"api_login_user\",\"password\":\"strong-password\",\"captcha\":\"" + answer + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andExpect(jsonPath("$.data.username").value("api_login_user"));

        org.assertj.core.api.Assertions.assertThat(session.getId()).isNotEqualTo(originalSessionId);

        mvc.perform(get("/api/v1/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andExpect(jsonPath("$.data.role").value("PLAYER"));
    }

    @Test
    void staleAuthorizationVersionInvalidatesApiSessionWithJson401() throws Exception {
        User account = users.findByUsernameKey(User.canonicalUsername("api_stale_user")).orElseGet(User::new);
        account.setUsername("api_stale_user");
        account.setPassword(passwordEncoder.encode("strong-password"));
        account.setRole(Role.PLAYER);
        account.setAuthorizationVersion(0);
        account = users.saveAndFlush(account);

        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/login").session(session)).andExpect(status().isOk());
        String answer = (String) session.getAttribute("captcha:login:code");
        mvc.perform(post("/api/v1/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"api_stale_user\",\"password\":\"strong-password\",\"captcha\":\"" + answer + "\"}"))
            .andExpect(status().isOk());

        User changed = users.findById(account.getId()).orElseThrow();
        changed.incrementAuthorizationVersion();
        users.saveAndFlush(changed);

        mvc.perform(get("/api/v1/session").session(session))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_STALE"))
            .andExpect(content().string(containsString("Сессия устарела")));
    }

    @Test
    void apiRegistrationUsesSharedRegistrationPolicy() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/register").session(session)).andExpect(status().isOk());
        String answer = (String) session.getAttribute("captcha:register:code");

        mvc.perform(post("/api/v1/auth/register")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"api_new_user\",\"password\":\"abc123\",\"passwordConfirm\":\"abc123\",\"captcha\":\"" + answer + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.username").value("api_new_user"));
    }
}
