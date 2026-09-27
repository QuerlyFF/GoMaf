package com.mafia.classicmafia.security;

import com.mafia.classicmafia.api.ApiAuthenticationEntryPoint;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Rejects sessions created against stale credentials, roles or moderator permissions.
 * The immutable database id is the session identity; username is only a checked attribute.
 */
public final class CredentialVersionFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public CredentialVersionFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof MafiaUserPrincipal principal) {
            var account = users.findSecurityAccountById(principal.userId());
            boolean valid = account.isPresent()
                && account.get().credentialVersion() == principal.credentialVersion()
                && account.get().authorizationVersion() == principal.authorizationVersion()
                && account.get().role().name().equals(principal.roleName())
                && User.canonicalUsername(account.get().username())
                    .equals(User.canonicalUsername(principal.getUsername()));
            if (!valid) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                if (isApiRequest(request)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType("application/json");
                    response.getWriter().write(ApiAuthenticationEntryPoint.errorJson(
                        request, 401, "SESSION_STALE", "Сессия устарела. Войдите снова."
                    ));
                } else {
                    response.sendRedirect(request.getContextPath() + "/login?error=session");
                }
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.equals("/api") || uri.startsWith("/api/");
    }
}
