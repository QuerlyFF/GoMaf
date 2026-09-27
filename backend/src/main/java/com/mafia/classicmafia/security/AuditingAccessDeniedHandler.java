package com.mafia.classicmafia.security;

import com.mafia.classicmafia.api.ApiAuthenticationEntryPoint;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Records authorization failures without exposing internal exception details to the client.
 */
public final class AuditingAccessDeniedHandler implements AccessDeniedHandler {
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final AccessDeniedHandler mvcDelegate;

    public AuditingAccessDeniedHandler(CurrentUserService currentUserService, AuditLogService auditLogService) {
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        AccessDeniedHandlerImpl handler = new AccessDeniedHandlerImpl();
        handler.setErrorPage("/forbidden");
        this.mvcDelegate = handler;
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        User actor = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            try {
                actor = currentUserService.require(authentication);
            } catch (RuntimeException ignored) {
                // Authentication may be synthetic in tests or already stale. Never fail the 403 path because of audit lookup.
            }
        }
        auditLogService.denied(
            "HTTP_ACCESS",
            actor,
            request.getMethod() + " " + request.getRequestURI(),
            "authorization"
        );

        if (isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write(ApiAuthenticationEntryPoint.errorJson(
                request, 403, "ACCESS_DENIED", "Недостаточно прав."
            ));
            return;
        }
        mvcDelegate.handle(request, response, accessDeniedException);
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
