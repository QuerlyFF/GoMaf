package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.Game;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.projection.GameDetailProjection;
import com.mafia.classicmafia.repository.projection.TournamentGameReadProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GameRepository extends JpaRepository<Game, Long> {
    List<Game> findAllByOrderByGameDateDescIdDesc();

    @EntityGraph(attributePaths = {"tournament"})
    @Query("select g from Game g where g.hiddenFromPublic = false and (:q = '' or lower(g.name) like lower(concat('%', :q, '%')) or lower(g.host) like lower(concat('%', :q, '%')))")
    Page<Game> searchPublic(@Param("q") String q, Pageable pageable);

    @Query("select g.id from Game g where g.hiddenFromPublic = false order by g.id asc")
    List<Long> findPublicGameIds();

    @Query("""
        select new com.mafia.classicmafia.repository.projection.GameDetailProjection(
            g.id, g.name, g.gameDate, g.host, g.winner, g.summary, g.hiddenFromPublic, t.id, t.name
        )
        from Game g
        left join g.tournament t
        where g.id = :id
        """)
    Optional<GameDetailProjection> findDetailProjectionById(@Param("id") Long id);

    @Query("select count(u) from Game g join g.hiddenAllowedUsers u where g.id = :gameId and u.id = :userId")
    long countVisibilityUser(@Param("gameId") Long gameId, @Param("userId") Long userId);

    @Query("select u.username from Game g join g.hiddenAllowedUsers u where g.id = :gameId and u.role in :roles order by u.username asc")
    List<String> findVisibilityUsernames(@Param("gameId") Long gameId, @Param("roles") Collection<Role> roles);

    @EntityGraph(attributePaths = {"players", "players.user", "tournament"})
    @Query("select distinct g from Game g where g.id = :id")
    Optional<Game> findDetailedById(@Param("id") Long id);

    boolean existsByTournamentId(Long tournamentId);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.TournamentGameReadProjection(
            g.id, g.name, g.gameDate, g.host, g.hiddenFromPublic
        )
        from Game g
        where g.tournament.id = :tournamentId
        order by g.gameDate asc, g.id asc
        """)
    List<TournamentGameReadProjection> findTournamentReadRows(@Param("tournamentId") Long tournamentId);

    @Query("""
        select g.id
        from Game g
        join g.hiddenAllowedUsers u
        where g.tournament.id = :tournamentId and u.id = :userId
        """)
    List<Long> findTournamentVisibleGameIds(
        @Param("tournamentId") Long tournamentId,
        @Param("userId") Long userId
    );
}
