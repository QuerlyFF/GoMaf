package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.SecurityRateLimitBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;

public interface SecurityRateLimitBucketRepository extends JpaRepository<SecurityRateLimitBucket, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SecurityRateLimitBucket b where b.bucketKey = :key")
    Optional<SecurityRateLimitBucket> findLocked(@Param("key") String key);

    @Modifying
    @Query("delete from SecurityRateLimitBucket b where b.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
