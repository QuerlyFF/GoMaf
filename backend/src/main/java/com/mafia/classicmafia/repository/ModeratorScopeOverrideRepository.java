package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.ModeratorScopeOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModeratorScopeOverrideRepository extends JpaRepository<ModeratorScopeOverride, Long> {
    List<ModeratorScopeOverride> findAllByUserId(Long userId);
    Optional<ModeratorScopeOverride> findByUserIdAndScope(Long userId, ModeratorScope scope);
    void deleteAllByUserId(Long userId);
}
