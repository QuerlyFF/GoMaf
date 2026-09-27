package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.CsrfResponse;
import com.mafia.classicmafia.api.dto.LoginRequest;
import com.mafia.classicmafia.api.dto.RegisterRequest;
import com.mafia.classicmafia.api.dto.RegistrationResponse;
import com.mafia.classicmafia.api.dto.SessionResponse;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import com.mafia.classicmafia.security.LoginGateService;
import com.mafia.classicmafia.security.PersistentLoginService;
import com.mafia.classicmafia.security.SecuritySessionService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import com.mafia.classicmafia.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AuthApiController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final LoginGateService loginGateService;
    private final RegistrationService registrationService;
    private final CurrentUserService currentUserService;
    private final ModeratorPermissionService moderatorPermissionService;
    private final SecuritySessionService securitySessions;
    private final PersistentLoginService persistentLogins;

    public AuthApiController(
        AuthenticationManager authenticationManager,
        SecurityContextRepository securityContextRepository,
        LoginGateService loginGateService,
        RegistrationService registrationService,
        CurrentUserService currentUserService,
        ModeratorPermissionService moderatorPermissionService,
        SecuritySessionService securitySessions,
        PersistentLoginService persistentLogins
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.loginGateService = loginGateService;
        this.registrationService = registrationService;
        this.currentUserService = currentUserService;
        this.moderatorPermissionService = moderatorPermissionService;
        this.securitySessions = securitySessions;
        this.persistentLogins = persistentLogins;
    }

    @GetMapping("/session")
    ApiDataResponse<SessionResponse> session(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equals(authentication.getPrincipal())) {
            return new ApiDataResponse<>(SessionResponse.anonymous());
        }
        SecurityAccountProjection user = currentUserService.requireSecurity(authentication);
        return new ApiDataResponse<>(sessionResponse(user));
    }

    @GetMapping("/csrf")
    ApiDataResponse<CsrfResponse> csrf(CsrfToken token) {
        return new ApiDataResponse<>(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @PostMapping("/auth/login")
    ApiDataResponse<SessionResponse> login(
        @RequestBody LoginRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        LoginGateService.Result gate = loginGateService.evaluate(request, body.username(), body.password(), body.captcha());
        switch (gate) {
            case INVALID_CREDENTIAL_SHAPE -> throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIALS_INVALID", "Некорректный логин или пароль.");
            case INVALID_CAPTCHA -> throw new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID", "Неверный или устаревший код с картинки.");
            case RATE_LIMITED -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "Слишком много попыток входа. Попробуйте позже.");
            case ALLOWED -> { }
        }

        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password())
            );
        } catch (AuthenticationException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Неверный логин или пароль.");
        }

        SecurityAccountProjection user = currentUserService.requireSecurity(authenticated);
        completeAuthentication(authenticated, user, request, response);
        persistentLogins.issue(user, response);
        return new ApiDataResponse<>(sessionResponse(user));
    }

    @PostMapping("/auth/register")
    ResponseEntity<ApiDataResponse<RegistrationResponse>> register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        RegistrationService.Result result = registrationService.register(
            body.username(), body.password(), body.passwordConfirm(), body.captcha(), request
        );
        if (!result.success()) {
            HttpStatus status = result.code().contains("RATE_LIMIT") || result.code().contains("CONNECTION_LIMIT")
                ? HttpStatus.TOO_MANY_REQUESTS
                : result.code().equals("USERNAME_TAKEN") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            throw new ApiException(status, result.code(), result.message());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new ApiDataResponse<>(new RegistrationResponse(result.userId(), result.username())));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        persistentLogins.revokeCurrent(request, response);
        var session = request.getSession(false);
        if (session != null) {
            securitySessions.remove(session);
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.addHeader("Set-Cookie", "MAFIASESSION=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax");
        return ResponseEntity.noContent().build();
    }

    private void completeAuthentication(Authentication authentication, SecurityAccountProjection user, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        securitySessions.register(user.id(), user.role(), session);
    }

    private SessionResponse sessionResponse(SecurityAccountProjection user) {
        List<String> permissions;
        if (user.role() == Role.ADMIN) {
            permissions = Arrays.stream(ModeratorScope.values()).map(Enum::name).toList();
        } else if (user.role() == Role.MODERATOR) {
            permissions = Arrays.stream(ModeratorScope.values())
                .filter(scope -> moderatorPermissionService.isAllowed(user.id(), user.role(), scope))
                .map(Enum::name)
                .toList();
        } else {
            permissions = List.of();
        }
        return new SessionResponse(true, user.id(), user.username(), user.role().name(), permissions);
    }

}
