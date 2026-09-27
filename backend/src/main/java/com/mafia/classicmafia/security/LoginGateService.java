package com.mafia.classicmafia.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class LoginGateService {
    private static final String USERNAME_PATTERN = "[A-Za-zА-Яа-яЁё0-9_]{3,32}";

    private final CaptchaService captchaService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final IpHashService ipHashService;

    public LoginGateService(
        CaptchaService captchaService,
        RateLimitService rateLimitService,
        ClientIpResolver clientIpResolver,
        IpHashService ipHashService
    ) {
        this.captchaService = captchaService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.ipHashService = ipHashService;
    }

    public Result evaluate(HttpServletRequest request, String username, String password, String captcha) {
        if (username == null || !username.matches(USERNAME_PATTERN) || password == null || password.length() > 64
            || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return Result.INVALID_CREDENTIAL_SHAPE;
        }

        HttpSession session = request.getSession(false);
        if (session == null || !captchaService.validate(session, "login", captcha)) {
            return Result.INVALID_CAPTCHA;
        }

        String ipHash = ipHashService.hash(clientIpResolver.resolve(request));
        if (!rateLimitService.allowLoginAttempt(ipHash, username)) {
            return Result.RATE_LIMITED;
        }
        return Result.ALLOWED;
    }

    public enum Result {
        ALLOWED,
        INVALID_CREDENTIAL_SHAPE,
        INVALID_CAPTCHA,
        RATE_LIMITED
    }
}
