package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.GameVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GameVideoRepository extends JpaRepository<GameVideo, Long> {
    @Query("select v.url from GameVideo v where v.game.id = :gameId order by v.position asc, v.id asc")
    List<String> findUrlsByGameId(@Param("gameId") Long gameId);
}
