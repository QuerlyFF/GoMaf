package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.PersistentLoginToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PersistentLoginTokenRepository extends JpaRepository<PersistentLoginToken, String> {
    List<PersistentLoginToken> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    @Modifying
    long deleteByUserId(Long userId);

    @Modifying
    @Query("delete from PersistentLoginToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
