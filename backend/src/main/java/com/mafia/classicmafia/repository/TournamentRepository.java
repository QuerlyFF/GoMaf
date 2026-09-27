package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {
    @Query("select distinct t from Tournament t left join fetch t.dates order by t.createdAt desc")
    List<Tournament> findAllDetailedByOrderByCreatedAtDesc();

    @Query("select distinct t from Tournament t left join fetch t.dates where t.id = :id")
    Optional<Tournament> findDetailedById(@Param("id") Long id);

    @Query("select t.id from Tournament t order by t.id asc")
    List<Long> findPublicTournamentIds();
}
