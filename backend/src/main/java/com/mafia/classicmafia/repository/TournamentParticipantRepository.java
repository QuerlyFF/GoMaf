package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.TournamentParticipant;
import com.mafia.classicmafia.repository.projection.TournamentParticipantReadProjection;
import com.mafia.classicmafia.repository.projection.TournamentViewerStateProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TournamentParticipantRepository extends JpaRepository<TournamentParticipant, Long> {
    Optional<TournamentParticipant> findByTournamentIdAndUserId(Long tournamentId, Long userId);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.TournamentParticipantReadProjection(
            u.id, u.username, p.invited, p.participating
        )
        from TournamentParticipant p
        join p.user u
        where p.tournament.id = :tournamentId
        order by u.username asc
        """)
    List<TournamentParticipantReadProjection> findReadRows(@Param("tournamentId") Long tournamentId);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.TournamentViewerStateProjection(
            p.invited, p.participating
        )
        from TournamentParticipant p
        where p.tournament.id = :tournamentId and p.user.id = :userId
        """)
    Optional<TournamentViewerStateProjection> findViewerState(
        @Param("tournamentId") Long tournamentId,
        @Param("userId") Long userId
    );

    long countByTournamentIdAndParticipatingTrue(Long tournamentId);
}
