package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.GamePlayer;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.projection.GameDetailPlayerProjection;
import com.mafia.classicmafia.repository.projection.PlayerGameProjection;
import com.mafia.classicmafia.repository.projection.PlayerStatsRowProjection;
import com.mafia.classicmafia.repository.projection.RankingSourceProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, Long> {
    @Query("""
        select new com.mafia.classicmafia.repository.projection.GameDetailPlayerProjection(
            gp.seatNumber, u.id, u.role, gp.displayName, gp.role, gp.firstKilled,
            gp.bestMove1, gp.bestMove2, gp.bestMove3, gp.victoryPoints, gp.firstKilledPoints,
            gp.bestMovePoints, gp.bonusPoints, gp.penaltyPoints, gp.totalPoints, gp.won
        )
        from GamePlayer gp
        left join gp.user u
        where gp.game.id = :gameId
        order by gp.seatNumber asc
        """)
    List<GameDetailPlayerProjection> findDetailRowsByGameId(@Param("gameId") Long gameId);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.RankingSourceProjection(
            u.id, u.username, gp.displayName, gp.won, gp.totalPoints,
            gp.firstKilledPoints, gp.bestMovePoints, gp.bonusPoints, gp.penaltyPoints
        )
        from GamePlayer gp
        join gp.game g
        left join gp.user u
        where g.hiddenFromPublic = false
          and (u is null or u.role in :roles)
        order by gp.id asc
        """)
    List<RankingSourceProjection> findPublicRankingRows(@Param("roles") Collection<Role> roles);

    @Query(
        value = """
            select new com.mafia.classicmafia.repository.projection.PlayerGameProjection(
                g.id, g.name, g.gameDate, gp.role, gp.totalPoints,
                gp.firstKilledPoints, gp.bestMovePoints, gp.bonusPoints, gp.penaltyPoints, gp.won
            )
            from GamePlayer gp
            join gp.game g
            where gp.user.id = :userId
              and (
                g.hiddenFromPublic = false
                or :viewerIsAdmin = true
                or exists (
                    select 1
                    from Game visibilityGame
                    join visibilityGame.hiddenAllowedUsers allowed
                    where visibilityGame.id = g.id
                      and allowed.id = :viewerId
                )
              )
            order by g.gameDate desc, g.id desc
            """,
        countQuery = """
            select count(gp)
            from GamePlayer gp
            join gp.game g
            where gp.user.id = :userId
              and (
                g.hiddenFromPublic = false
                or :viewerIsAdmin = true
                or exists (
                    select 1
                    from Game visibilityGame
                    join visibilityGame.hiddenAllowedUsers allowed
                    where visibilityGame.id = g.id
                      and allowed.id = :viewerId
                )
              )
            """
    )
    Page<PlayerGameProjection> findVisibleHistoryByUserId(
        @Param("userId") Long userId,
        @Param("viewerId") Long viewerId,
        @Param("viewerIsAdmin") boolean viewerIsAdmin,
        Pageable pageable
    );

    @Query("""
        select distinct gp.user.id
        from GamePlayer gp
        where gp.game.id = :gameId and gp.user is not null
        """)
    List<Long> findRegisteredUserIdsByGameId(@Param("gameId") Long gameId);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.PlayerStatsRowProjection(
            gp.user.id, gp.won, g.winner, gp.firstKilledPoints, gp.bestMovePoints, gp.bonusPoints, gp.penaltyPoints
        )
        from GamePlayer gp
        join gp.game g
        where gp.user.id in :userIds
        """)
    List<PlayerStatsRowProjection> findStatsRowsByUserIds(@Param("userIds") Collection<Long> userIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update GamePlayer gp set gp.user = null where gp.user.id = :userId")
    int detachUserFromHistory(@Param("userId") Long userId);
}
