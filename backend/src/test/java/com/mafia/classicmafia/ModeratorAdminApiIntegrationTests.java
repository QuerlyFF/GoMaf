package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.MafiaUserPrincipal;
import com.mafia.classicmafia.security.SecuritySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
class ModeratorAdminApiIntegrationTests {
    private static final String PASSWORD = "strong-password-123";
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SecuritySessionService securitySessions;

    private MockMvc mvc;
    private String clientIp;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clientIp = "2001:db8:16:" + Integer.toHexString(IP_SEQUENCE.getAndIncrement()) + "::1";
    }

    @Test
    void moderatorConsoleIsCanonicalAdminOnlyAndDoesNotExposeSecrets() throws Exception {
        User player = createUser("wp11016_player_" + suffix(), Role.PLAYER);
        mvc.perform(get("/api/v1/admin/moderators/defaults")
                .with(isolatedIp())
                .with(TestAuthentication.authenticated(player, securitySessions)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));

        User admin = createAdmin();
        mvc.perform(get("/api/v1/admin/moderators/defaults")
                .with(isolatedIp())
                .with(TestAuthentication.authenticated(admin, securitySessions)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scopes.length()").value(ModeratorScope.values().length))
            .andExpect(jsonPath("$.data.defaults.NEWS_MANAGE").exists())
            .andExpect(jsonPath("$.data.password").doesNotExist())
            .andExpect(jsonPath("$.data.authorizationVersion").doesNotExist());
    }

    @Test
    void mutationsRequireCsrfAndCurrentAdminPassword() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11016_csrf_" + suffix(), Role.PLAYER);

        mvc.perform(post("/api/v1/admin/moderators/" + player.getId())
                .with(isolatedIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/moderators/" + player.getId())
                .with(isolatedIp())
                .with(TestAuthentication.authenticated(admin, securitySessions))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong-password\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REAUTH_FAILED"));

        assertThat(users.findById(player.getId()).orElseThrow().getRole()).isEqualTo(Role.PLAYER);
    }

    @Test
    void canonicalAdminCanAssignOverrideAndRemoveModerator() throws Exception {
        User admin = createAdmin();
        User player = createUser("wp11016_assign_" + suffix(), Role.PLAYER);
        int before = player.getAuthorizationVersion();
        var adminAuth = TestAuthentication.authenticated(admin, securitySessions);

        mvc.perform(post("/api/v1/admin/moderators/" + player.getId())
                .with(isolatedIp()).with(adminAuth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(player.getId()));

        User moderator = users.findById(player.getId()).orElseThrow();
        assertThat(moderator.getRole()).isEqualTo(Role.MODERATOR);
        assertThat(moderator.getAuthorizationVersion()).isEqualTo(before + 1);

        String scopes = scopeDecisionsJson("ALLOW");
        mvc.perform(put("/api/v1/admin/moderators/" + player.getId() + "/scopes")
                .with(isolatedIp()).with(adminAuth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopes\":" + scopes + ",\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/moderators?q=" + player.getUsername())
                .with(isolatedIp()).with(adminAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].username").value(player.getUsername()))
            .andExpect(jsonPath("$.items[0].scopes.NEWS_MANAGE.decision").value("ALLOW"))
            .andExpect(jsonPath("$.items[0].scopes.NEWS_MANAGE.effective").value(true));

        mvc.perform(delete("/api/v1/admin/moderators/" + player.getId())
                .with(isolatedIp()).with(adminAuth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        assertThat(users.findById(player.getId()).orElseThrow().getRole()).isEqualTo(Role.PLAYER);
    }

    @Test
    void defaultsAreAllOrNothingAndInvalidateModeratorAuthorizationVersion() throws Exception {
        User admin = createAdmin();
        User moderator = createUser("wp11016_defaults_" + suffix(), Role.MODERATOR);
        int before = moderator.getAuthorizationVersion();
        var adminAuth = TestAuthentication.authenticated(admin, securitySessions);

        mvc.perform(put("/api/v1/admin/moderators/defaults")
                .with(isolatedIp()).with(adminAuth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaults\":{\"NEWS_MANAGE\":true},\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MODERATOR_ADMIN_INVALID"));

        mvc.perform(put("/api/v1/admin/moderators/defaults")
                .with(isolatedIp()).with(adminAuth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaults\":" + defaultMapJson() + ",\"currentPassword\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());

        assertThat(users.findById(moderator.getId()).orElseThrow().getAuthorizationVersion()).isGreaterThanOrEqualTo(before);
    }

    @Test
    void everyModeratorAdminEndpointRejectsPlayerAndModeratorRoles() throws Exception {
        User player = createUser("wp11016_surface_player_" + suffix(), Role.PLAYER);
        User moderator = createUser("wp11016_surface_mod_" + suffix(), Role.MODERATOR);
        assertAdminOnlySurface(player);
        assertAdminOnlySurface(moderator);
    }

    private void assertAdminOnlySurface(User actor) throws Exception {
        var auth = TestAuthentication.authenticated(actor, securitySessions);
        mvc.perform(get("/api/v1/admin/moderators/defaults").with(isolatedIp()).with(auth)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(get("/api/v1/admin/moderators").with(isolatedIp()).with(auth)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(get("/api/v1/admin/moderators/eligible").with(isolatedIp()).with(auth)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(put("/api/v1/admin/moderators/defaults").with(isolatedIp()).with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(post("/api/v1/admin/moderators/999999").with(isolatedIp()).with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(put("/api/v1/admin/moderators/999999/scopes").with(isolatedIp()).with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(delete("/api/v1/admin/moderators/999999").with(isolatedIp()).with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
    }

    private String defaultMapJson() {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < ModeratorScope.values().length; i++) {
            if (i > 0) builder.append(',');
            ModeratorScope scope = ModeratorScope.values()[i];
            builder.append('"').append(scope.name()).append("\":").append(scope == ModeratorScope.NEWS_MANAGE);
        }
        return builder.append('}').toString();
    }

    private String scopeDecisionsJson(String decision) {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < ModeratorScope.values().length; i++) {
            if (i > 0) builder.append(',');
            builder.append('"').append(ModeratorScope.values()[i].name()).append("\":\"").append(decision).append('"');
        }
        return builder.append('}').toString();
    }

    private User createAdmin() {
        User admin = users.findByUsernameKey("admin").orElseGet(User::new);
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        admin = users.saveAndFlush(admin);
        return admin;
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

    private RequestPostProcessor isolatedIp() {
        return request -> { request.setRemoteAddr(clientIp); return request; };
    }

    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 8); }
}
