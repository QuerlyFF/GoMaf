package com.mafia.classicmafia.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
        throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(errorJson(request, 401, "AUTHENTICATION_REQUIRED", "Требуется вход в аккаунт."));
    }

    public static String errorJson(HttpServletRequest request, int status, String code, String message) {
        Object requestId = request.getAttribute(ApiRequestContext.REQUEST_ID_ATTRIBUTE);
        String safeRequestId = requestId == null ? "" : requestId.toString();
        return "{\"status\":" + status
            + ",\"code\":\"" + code
            + "\",\"message\":\"" + message
            + "\",\"fieldErrors\":[],\"requestId\":\"" + safeRequestId + "\"}";
    }
}
