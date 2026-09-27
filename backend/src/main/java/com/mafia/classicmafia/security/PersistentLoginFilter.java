package com.mafia.classicmafia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Restores a normal server-side session from a rotated, server-tracked persistent-login cookie. */
public final class PersistentLoginFilter extends OncePerRequestFilter {
    private final PersistentLoginService persistentLogins;
    private final SecurityContextRepository securityContextRepository;
    private final SecuritySessionService securitySessions;

    public PersistentLoginFilter(
        PersistentLoginService persistentLogins,
        SecurityContextRepository securityContextRepository,
        SecuritySessionService securitySessions
    ) {
        this.persistentLogins = persistentLogins;
        this.securityContextRepository = securityContextRepository;
        this.securitySessions = securitySessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || !existing.isAuthenticated() || "anonymousUser".equals(existing.getPrincipal())) {
            var restored = persistentLogins.consume(request, response);
            if (restored.isPresent()) {
                var user = restored.get();
                var principal = new MafiaUserPrincipal(
                    user.id(), user.username(), null, user.role().name(),
                    user.credentialVersion(), user.authorizationVersion()
                );
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()
                );
                HttpSession session = request.getSession(true);
                request.changeSessionId();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
                securitySessions.register(user.id(), user.role(), session);
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) uri = uri.substring(context.length());
        return uri.equals("/api/v1/auth/login")
            || uri.equals("/api/v1/auth/register")
            || uri.equals("/api/v1/auth/logout")
            || uri.startsWith("/captcha/")
            || uri.equals("/favicon.png")
            || uri.equals("/favicon.ico")
            || uri.equals("/robots.txt")
            || uri.equals("/sitemap.xml")
            || uri.equals("/not-found")
            || uri.startsWith("/assets/")
            || uri.startsWith("/css/")
            || uri.startsWith("/fonts/");
    }
}
