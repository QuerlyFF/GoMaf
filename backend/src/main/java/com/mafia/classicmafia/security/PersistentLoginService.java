package com.mafia.classicmafia.security;

import com.mafia.classicmafia.entity.PersistentLoginToken;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.PersistentLoginTokenRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PersistentLoginService {
    public static final String COOKIE_NAME = "GOMAF_REMEMBER";
    private static final Duration USER_LIFETIME = Duration.ofDays(90);
    private static final Duration ADMIN_LIFETIME = Duration.ofDays(30);
    private static final int SELECTOR_BYTES = 18;
    private static final int VALIDATOR_BYTES = 32;

    private final PersistentLoginTokenRepository tokens;
    private final UserRepository users;
    private final DatabaseLockService locks;
    private final SecureRandom random = new SecureRandom();
    private final boolean secureCookie;

    public PersistentLoginService(
        PersistentLoginTokenRepository tokens,
        UserRepository users,
        DatabaseLockService locks,
        @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie
    ) {
        this.tokens = tokens;
        this.users = users;
        this.locks = locks;
        this.secureCookie = secureCookie;
    }

    @Transactional
    public void issue(SecurityAccountProjection user, HttpServletResponse response) {
        Instant now = Instant.now();
        tokens.deleteExpired(now.minus(Duration.ofMinutes(5)));
        locks.lock("persistent-login-user", String.valueOf(user.id()));
        CreatedToken created = create(user, now);
        enforceCap(user.id(), user.role());
        writeCookie(response, created.rawValue(), lifetime(user.role()));
    }

    @Transactional
    public Optional<SecurityAccountProjection> consume(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookieValue(request);
        ParsedToken parsed = parse(raw);
        if (parsed == null) {
            if (raw != null) clearCookie(response);
            return Optional.empty();
        }

        locks.lock("persistent-login-selector", parsed.selector());
        PersistentLoginToken token = tokens.findById(parsed.selector()).orElse(null);
        Instant now = Instant.now();
        if (token == null || !token.getExpiresAt().isAfter(now)) {
            if (token != null) tokens.delete(token);
            clearCookie(response);
            return Optional.empty();
        }

        SecurityAccountProjection user = users.findSecurityAccountById(token.getUserId()).orElse(null);
        if (user == null
            || !token.getRoleName().equals(user.role().name())
            || token.getCredentialVersion() != user.credentialVersion()
            || token.getAuthorizationVersion() != user.authorizationVersion()
            || !constantTimeEquals(token.getValidatorHash(), sha256(parsed.validator()))) {
            tokens.delete(token);
            clearCookie(response);
            return Optional.empty();
        }

        // Rotate both selector and validator. The browser never keeps a reusable old token
        // after a successful persistent login, while the database stores only a SHA-256 hash.
        tokens.delete(token);
        CreatedToken rotated = create(user, now);
        enforceCap(user.id(), user.role());
        writeCookie(response, rotated.rawValue(), lifetime(user.role()));
        return Optional.of(user);
    }

    @Transactional
    public void revokeCurrent(HttpServletRequest request, HttpServletResponse response) {
        ParsedToken parsed = parse(cookieValue(request));
        if (parsed != null) tokens.deleteById(parsed.selector());
        clearCookie(response);
    }

    @Transactional
    public void invalidateUser(long userId) {
        tokens.deleteByUserId(userId);
    }

    private CreatedToken create(SecurityAccountProjection user, Instant now) {
        String selector = randomToken(SELECTOR_BYTES);
        String validator = randomToken(VALIDATOR_BYTES);
        PersistentLoginToken row = new PersistentLoginToken();
        row.setSelector(selector);
        row.setValidatorHash(sha256(validator));
        row.setUserId(user.id());
        row.setRoleName(user.role().name());
        row.setCredentialVersion(user.credentialVersion());
        row.setAuthorizationVersion(user.authorizationVersion());
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(lifetime(user.role())));
        row.setLastUsedAt(now);
        tokens.saveAndFlush(row);
        return new CreatedToken(selector + "." + validator);
    }

    private void enforceCap(long userId, Role role) {
        int cap = role == Role.ADMIN ? 1 : 3;
        var all = tokens.findAllByUserIdOrderByCreatedAtAsc(userId);
        int excess = all.size() - cap;
        for (int i = 0; i < excess; i++) tokens.delete(all.get(i));
        tokens.flush();
    }

    private Duration lifetime(Role role) {
        return role == Role.ADMIN ? ADMIN_LIFETIME : USER_LIFETIME;
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private ParsedToken parse(String raw) {
        if (raw == null || raw.length() > 160) return null;
        int dot = raw.indexOf('.');
        if (dot < 16 || dot != raw.lastIndexOf('.') || dot >= raw.length() - 20) return null;
        String selector = raw.substring(0, dot);
        String validator = raw.substring(dot + 1);
        if (!selector.matches("[A-Za-z0-9_-]+") || !validator.matches("[A-Za-z0-9_-]+")) return null;
        return new ParsedToken(selector, validator);
    }

    private void writeCookie(HttpServletResponse response, String value, Duration lifetime) {
        String header = COOKIE_NAME + "=" + value
            + "; Max-Age=" + lifetime.toSeconds()
            + "; Path=/; HttpOnly; SameSite=Lax"
            + (secureCookie ? "; Secure" : "");
        response.addHeader("Set-Cookie", header);
    }

    private void clearCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", COOKIE_NAME + "=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax" + (secureCookie ? "; Secure" : ""));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен.", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private record ParsedToken(String selector, String validator) { }
    private record CreatedToken(String rawValue) { }
}
