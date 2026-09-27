package com.mafia.classicmafia.security;

import com.mafia.classicmafia.repository.SecurityRateLimitBucketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RateLimitServiceTests {
    @Autowired RateLimitService service;
    @Autowired SecurityRateLimitBucketRepository buckets;

    @BeforeEach
    void clearBuckets() {
        buckets.deleteAll();
    }

    @Test
    void fixedWindowRejectsAttemptOverLimit() {
        assertThat(service.consume("test:x", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(service.consume("test:x", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(service.consume("test:x", 2, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void loginLimiterUsesIpAndUsernamePair() {
        for (int i = 0; i < 8; i++) {
            assertThat(service.allowLoginAttempt("ip-a", "Player")).isTrue();
        }
        assertThat(service.allowLoginAttempt("ip-a", "Player")).isFalse();
        assertThat(service.allowLoginAttempt("ip-b", "Player")).isTrue();
    }

    @Test
    void thereIsNoGlobalUsernameLockoutAcrossIps() {
        for (int i = 0; i < 40; i++) {
            assertThat(service.allowLoginAttempt("ip-" + i, "admin")).isTrue();
        }
    }

    @Test
    void exhaustedIpLimitDoesNotCreateUnlimitedPairBuckets() {
        for (int i = 0; i < 30; i++) {
            assertThat(service.allowLoginAttempt("ip-one", "user" + i)).isTrue();
        }
        long before = service.counterCount();
        for (int i = 30; i < 1030; i++) {
            assertThat(service.allowLoginAttempt("ip-one", "user" + i)).isFalse();
        }
        assertThat(service.counterCount()).isEqualTo(before);
    }
}
