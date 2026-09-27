package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.CaptchaService;
import com.mafia.classicmafia.security.ClientIpResolver;
import com.mafia.classicmafia.security.IpHashService;
import com.mafia.classicmafia.security.PasswordPolicy;
import com.mafia.classicmafia.security.RateLimitService;
import com.mafia.classicmafia.security.DatabaseLockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RegistrationService {
    private static final String USERNAME_PATTERN = "[A-Za-zА-Яа-яЁё0-9_]{3,32}";
    private static final long MAX_REGISTRATIONS_PER_HOUR = 3;
    private static final long MAX_REGISTRATIONS_PER_DAY = 10;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final CaptchaService captchaService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final IpHashService ipHashService;
    private final DatabaseLockService databaseLocks;

    public RegistrationService(
        UserRepository users,
        PasswordEncoder passwordEncoder,
        PasswordPolicy passwordPolicy,
        CaptchaService captchaService,
        RateLimitService rateLimitService,
        ClientIpResolver clientIpResolver,
        IpHashService ipHashService,
        DatabaseLockService databaseLocks
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.captchaService = captchaService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.ipHashService = ipHashService;
        this.databaseLocks = databaseLocks;
    }

    @Transactional
    public Result register(
        String username,
        String password,
        String passwordConfirm,
        String captcha,
        HttpServletRequest request
    ) {
        String normalizedUsername = username == null ? "" : username.trim();
        String ipHash = ipHashService.hash(clientIpResolver.resolve(request));

        HttpSession session = request.getSession(false);
        if (session == null || !captchaService.validate(session, "register", captcha)) {
            return Result.failure("CAPTCHA_INVALID", "Неверный или устаревший код с картинки.", normalizedUsername);
        }
        if (!rateLimitService.allowRegistrationAttempt(ipHash)) {
            return Result.failure("REGISTRATION_RATE_LIMITED", "Слишком много попыток регистрации. Попробуйте позже.", normalizedUsername);
        }
        if (!normalizedUsername.matches(USERNAME_PATTERN)) {
            return Result.failure("USERNAME_INVALID", "Ник: 3-32 символа, буквы, цифры и _.", normalizedUsername);
        }
        if ("admin".equals(User.canonicalUsername(normalizedUsername))) {
            return Result.failure("USERNAME_RESERVED", "Ник admin зарезервирован системой.", normalizedUsername);
        }

        String passwordError = passwordPolicy.validate(password);
        if (passwordError != null) {
            return Result.failure("PASSWORD_INVALID", passwordError, normalizedUsername);
        }
        if (!password.equals(passwordConfirm)) {
            return Result.failure("PASSWORD_CONFIRMATION_MISMATCH", "Пароли не совпадают.", normalizedUsername);
        }
        String usernameKey = User.canonicalUsername(normalizedUsername);
        if (users.existsByUsernameKey(usernameKey)) {
            return Result.failure("USERNAME_TAKEN", "Такой ник уже занят.", normalizedUsername);
        }

        // Lock the canonical username before the IP quota. This fixed lock order prevents both
        // duplicate-username races and transaction rollback caused by a late unique violation.
        // The quota lock then serializes count + successful insert for this privacy-preserving IP hash.
        databaseLocks.lock("registration-username", usernameKey);
        databaseLocks.lock("registration-quota", ipHash);

        if (users.existsByUsernameKey(usernameKey)) {
            return Result.failure("USERNAME_TAKEN", "Такой ник уже занят.", normalizedUsername);
        }

        LocalDateTime now = LocalDateTime.now();
        if (users.countByRegistrationIpHashAndCreatedAtAfter(ipHash, now.minusHours(1)) >= MAX_REGISTRATIONS_PER_HOUR
            || users.countByRegistrationIpHashAndCreatedAtAfter(ipHash, now.minusDays(1)) >= MAX_REGISTRATIONS_PER_DAY) {
            return Result.failure("REGISTRATION_CONNECTION_LIMIT", "С этого подключения создано слишком много аккаунтов. Попробуйте позже.", normalizedUsername);
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(Role.PLAYER);
        user.setRegistrationIpHash(ipHash);

        User saved = users.saveAndFlush(user);
        return Result.success(saved.getId(), saved.getUsername());
    }

    public record Result(boolean success, String code, String message, Long userId, String username) {
        static Result success(Long userId, String username) {
            return new Result(true, "REGISTERED", "Аккаунт создан.", userId, username);
        }

        static Result failure(String code, String message, String username) {
            return new Result(false, code, message, null, username);
        }
    }
}
