package com.mafia.classicmafia.security;

import com.mafia.classicmafia.entity.SecurityRateLimitBucket;
import com.mafia.classicmafia.repository.SecurityRateLimitBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimitService {
    private static final long MAX_COUNTERS = 50_000;
    private static final int CLEANUP_MASK = 255;

    private final SecurityRateLimitBucketRepository buckets;
    private final DatabaseLockService locks;
    private final IpHashService hashes;
    private final AtomicLong operations = new AtomicLong();

    public RateLimitService(SecurityRateLimitBucketRepository buckets, DatabaseLockService locks, IpHashService hashes) {
        this.buckets = buckets;
        this.locks = locks;
        this.hashes = hashes;
    }

    @Transactional
    public boolean allowCaptcha(String ipHash) {
        return consume(key("captcha", ipHash), 20, Duration.ofMinutes(1));
    }

    @Transactional
    public boolean allowLoginAttempt(String ipHash, String username) {
        if (!consume(key("login-ip", ipHash), 30, Duration.ofMinutes(15))) {
            return false;
        }
        String principal = normalize(username);
        return consume(key("login-pair", ipHash + ':' + principal), 8, Duration.ofMinutes(15));
    }


    @Transactional
    public boolean allowRegistrationAttempt(String ipHash) {
        return consume(key("register", ipHash), 12, Duration.ofHours(1));
    }

    @Transactional
    public boolean allowAvatarUpload(String ipHash, String username) {
        return consume(key("avatar", ipHash + ':' + normalize(username)), 12, Duration.ofHours(1));
    }

    @Transactional
    public boolean allowBannerUpload(String ipHash, String username) {
        return consume(key("profile-banner", ipHash + ':' + normalize(username)), 12, Duration.ofHours(1));
    }

    @Transactional
    public boolean allowSensitiveReauth(String ipHash, String username) {
        return consume(key("reauth", ipHash + ':' + normalize(username)), 10, Duration.ofMinutes(15));
    }

    @Transactional
    boolean consume(String bucketKey, int limit, Duration duration) {
        if (limit <= 0 || duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Некорректные параметры rate limit.");
        }
        Instant now = Instant.now();
        locks.lock("rate-limit-bucket", bucketKey);
        var existing = buckets.findLocked(bucketKey).orElse(null);
        if (existing == null) {
            locks.lock("rate-limit-capacity", "global");
            buckets.deleteExpired(now.minus(Duration.ofMinutes(5)));
            if (buckets.count() >= MAX_COUNTERS) {
                return false;
            }
            SecurityRateLimitBucket created = new SecurityRateLimitBucket();
            created.setBucketKey(bucketKey);
            created.setAttempts(1);
            created.setExpiresAt(now.plus(duration));
            created.setUpdatedAt(now);
            buckets.saveAndFlush(created);
            return true;
        }

        if (!existing.getExpiresAt().isAfter(now)) {
            existing.setAttempts(1);
            existing.setExpiresAt(now.plus(duration));
            existing.setUpdatedAt(now);
            buckets.save(existing);
            maybeCleanup(now);
            return true;
        }
        if (existing.getAttempts() >= limit) {
            maybeCleanup(now);
            return false;
        }
        existing.setAttempts(existing.getAttempts() + 1);
        existing.setUpdatedAt(now);
        buckets.save(existing);
        maybeCleanup(now);
        return true;
    }

    long counterCount() {
        return buckets.count();
    }

    private void maybeCleanup(Instant now) {
        if ((operations.incrementAndGet() & CLEANUP_MASK) == 0) {
            buckets.deleteExpired(now.minus(Duration.ofMinutes(5)));
        }
    }

    private String key(String scope, String material) {
        return scope + ':' + hashes.hash("rate-limit:" + scope + ':' + material);
    }

    private String normalize(String username) {
        if (username == null) {
            return "";
        }
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
