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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AdminAuthenticationIntegrationTests {
    private static final String PASSWORD = "strong-password-123";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User admin = users.findByUsernameKey("admin").orElseGet(User::new);
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        users.saveAndFlush(admin);
    }

    @Test
    void adminLoginCompletesWithoutSecondFactor() throws Exception {
        MockHttpSession session = loginAdmin();
        mvc.perform(get("/api/v1/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andExpect(jsonPath("$.data.username").value("admin"))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.mfaRequired").doesNotExist())
            .andExpect(jsonPath("$.data.mfaEnrollmentRequired").doesNotExist());
    }

    @Test
    void retiredMfaEndpointsAreNotMapped() throws Exception {
        MockHttpSession session = loginAdmin();
        mvc.perform(post("/api/v1/auth/mfa/enroll/start").session(session).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/auth/mfa/enroll/qr").session(session))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/mfa/enroll/confirm").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/mfa/verify").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
            .andExpect(status().isNotFound());
    }

    private MockHttpSession loginAdmin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/captcha/login").session(session)).andExpect(status().isOk());
        String answer = (String) session.getAttribute("captcha:login:code");
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD +
                    "\",\"captcha\":\"" + answer + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true));
        return session;
    }
}
