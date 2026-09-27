package com.mafia.classicmafia.security;

import com.mafia.classicmafia.api.ApiAuthenticationEntryPoint;
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

public final class SecuritySessionFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final SecuritySessionService securitySessions;

    public SecuritySessionFilter(UserRepository users, SecuritySessionService securitySessions) {
        this.users = users;
        this.securitySessions = securitySessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof MafiaUserPrincipal principal) {
            HttpSession session = request.getSession(false);
            var user = users.findSecurityAccountById(principal.userId()).orElse(null);
            boolean valid = session != null && user != null
                && securitySessions.validate(user.id(), user.role(), session);
            if (!valid) {
                SecurityContextHolder.clearContext();
                if (session != null) {
                    try { securitySessions.remove(session); } catch (RuntimeException ignored) { }
                    session.invalidate();
                }
                if (isApiRequest(request)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType("application/json");
                    response.getWriter().write(ApiAuthenticationEntryPoint.errorJson(
                        request, 401, "SESSION_EXPIRED", "Сессия истекла. Войдите снова."
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
        if (context != null && !context.isEmpty() && uri.startsWith(context)) uri = uri.substring(context.length());
        return uri.equals("/api") || uri.startsWith("/api/");
    }
}
