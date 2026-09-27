package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.ModeratorScopeDefault;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModeratorScopeDefaultRepository extends JpaRepository<ModeratorScopeDefault, ModeratorScope> {
}
