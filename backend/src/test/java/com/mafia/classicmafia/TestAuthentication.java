package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.security.MafiaUserPrincipal;
import com.mafia.classicmafia.security.SecuritySessionService;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

final class TestAuthentication {
    private TestAuthentication() {}

    static RequestPostProcessor authenticated(User user, SecuritySessionService securitySessions) {
        MockHttpSession session = new MockHttpSession();
        securitySessions.register(user, session);
        MafiaUserPrincipal principal = new MafiaUserPrincipal(
            user.getId(),
            user.getUsername(),
            user.getPassword(),
            user.getRole().name(),
            user.getCredentialVersion(),
            user.getAuthorizationVersion()
        );
        var token = new UsernamePasswordAuthenticationToken(
            principal,
            principal.getPassword(),
            principal.getAuthorities()
        );
        return request -> {
            request.setSession(session);
            return authentication(token).postProcessRequest(request);
        };
    }
}
