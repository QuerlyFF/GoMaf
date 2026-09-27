package com.mafia.classicmafia.security;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.SecuritySession;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.SecuritySessionRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class SecuritySessionService {
    private static final Duration ADMIN_ABSOLUTE_LIFETIME = Duration.ofDays(30);
    private static final Duration USER_ABSOLUTE_LIFETIME = Duration.ofDays(90);
    private static final Duration LAST_SEEN_WRITE_INTERVAL = Duration.ofMinutes(5);

    private final SecuritySessionRepository sessions;
    private final DatabaseLockService locks;

    public SecuritySessionService(SecuritySessionRepository sessions, DatabaseLockService locks) {
        this.sessions = sessions;
        this.locks = locks;
    }

    @Transactional
    public void register(User user, HttpSession httpSession) {
        register(user.getId(), user.getRole(), httpSession);
    }

    @Transactional
    public void register(long userId, Role role, HttpSession httpSession) {
        Instant now = Instant.now();
        sessions.deleteExpired(now.minus(Duration.ofMinutes(5)));
        locks.lock("security-session-user", String.valueOf(userId));

        SecuritySession row = new SecuritySession();
        row.setSessionHash(hashSessionId(httpSession.getId()));
        row.setUserId(userId);
        row.setRoleName(role.name());
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(lifetime(role)));
        row.setLastSeenAt(now);
        sessions.saveAndFlush(row);

        enforceCap(userId, role);
    }

    @Transactional
    public boolean validate(long userId, Role role, HttpSession httpSession) {
        Instant now = Instant.now();
        String sessionHash = hashSessionId(httpSession.getId());
        SecuritySession row = sessions.findById(sessionHash).orElse(null);
        if (row == null) {
            // Existing non-admin sessions from WP11-022 may be adopted. Existing ADMIN
            // sessions are deliberately invalidated so an ADMIN must reauthenticate after rollout.
            if (role == Role.ADMIN) return false;
            Instant created = Instant.ofEpochMilli(httpSession.getCreationTime());
            Instant expires = created.plus(USER_ABSOLUTE_LIFETIME);
            if (!expires.isAfter(now)) return false;

            // Serialize legacy adoption per user so rollout cannot temporarily bypass
            // the normal three-session cap through concurrent first requests. Recheck
            // after the lock because another request may have adopted this same session.
            locks.lock("security-session-user", String.valueOf(userId));
            row = sessions.findById(sessionHash).orElse(null);
            if (row == null) {
                row = new SecuritySession();
                row.setSessionHash(sessionHash);
                row.setUserId(userId);
                row.setRoleName(role.name());
                row.setCreatedAt(created);
                row.setExpiresAt(expires);
                row.setLastSeenAt(now);
                sessions.saveAndFlush(row);
                enforceCap(userId, role);
                // If this legacy session was one of the oldest sessions removed to enforce
                // the cap, reject it instead of silently recreating/untracking it.
                if (!sessions.existsById(sessionHash)) return false;
            }
        }
        if (!row.getUserId().equals(userId) || !row.getRoleName().equals(role.name())
            || !row.getExpiresAt().isAfter(now)) {
            sessions.delete(row);
            return false;
        }
        if (row.getLastSeenAt().plus(LAST_SEEN_WRITE_INTERVAL).isBefore(now)) {
            row.setLastSeenAt(now);
            sessions.save(row);
        }
        return true;
    }

    @Transactional
    public void remove(HttpSession httpSession) {
        if (httpSession != null) sessions.deleteById(hashSessionId(httpSession.getId()));
    }

    @Transactional
    public void invalidateUser(long userId) {
        sessions.deleteByUserId(userId);
    }


    private void enforceCap(long userId, Role role) {
        int cap = role == Role.ADMIN ? 1 : 3;
        var all = sessions.findAllByUserIdOrderByCreatedAtAsc(userId);
        int excess = all.size() - cap;
        for (int i = 0; i < excess; i++) {
            sessions.delete(all.get(i));
        }
        sessions.flush();
    }

    private Duration lifetime(Role role) {
        return role == Role.ADMIN ? ADMIN_ABSOLUTE_LIFETIME : USER_ABSOLUTE_LIFETIME;
    }

    String hashSessionId(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен.", exception);
        }
    }
}
