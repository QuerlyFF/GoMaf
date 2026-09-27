package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.SecuritySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SecuritySessionRepository extends JpaRepository<SecuritySession, String> {
    List<SecuritySession> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    @Modifying
    @Query("delete from SecuritySession s where s.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);

    @Modifying
    long deleteByUserId(Long userId);
}
