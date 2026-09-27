package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.security.ClientIpResolver;
import com.mafia.classicmafia.security.IpHashService;
import com.mafia.classicmafia.security.PasswordPolicy;
import com.mafia.classicmafia.security.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SensitiveActionService {
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final IpHashService ipHashService;
    private final AuditLogService auditLogService;

    public SensitiveActionService(
        CurrentUserService currentUserService,
        PasswordEncoder passwordEncoder,
        PasswordPolicy passwordPolicy,
        RateLimitService rateLimitService,
        ClientIpResolver clientIpResolver,
        IpHashService ipHashService,
        AuditLogService auditLogService
    ) {
        this.currentUserService = currentUserService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.ipHashService = ipHashService;
        this.auditLogService = auditLogService;
    }

    public User verify(Authentication authentication, String password, HttpServletRequest request) {
        User operator = currentUserService.require(authentication);
        String principalName = authentication == null ? "" : authentication.getName();
        String ipHash = ipHashService.hash(clientIpResolver.resolve(request));
        if (!rateLimitService.allowSensitiveReauth(ipHash, principalName)) {
            auditLogService.denied("SENSITIVE_REAUTH", operator, "self", "rate-limit");
            throw new SensitiveActionException(Reason.RATE_LIMITED, "Слишком много попыток подтверждения пароля. Попробуйте позже.");
        }
        if (!passwordPolicy.isVerificationInputAcceptable(password)
            || !passwordEncoder.matches(password, operator.getPassword())) {
            auditLogService.denied("SENSITIVE_REAUTH", operator, "self", "credential-mismatch");
            throw new SensitiveActionException(Reason.CREDENTIAL_MISMATCH, "Текущий пароль указан неверно.");
        }
        return operator;
    }

    public enum Reason {
        RATE_LIMITED,
        CREDENTIAL_MISMATCH
    }

    public static class SensitiveActionException extends RuntimeException {
        private final Reason reason;

        public SensitiveActionException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
