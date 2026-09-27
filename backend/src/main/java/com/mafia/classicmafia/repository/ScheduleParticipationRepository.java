package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.ScheduleParticipation;
import com.mafia.classicmafia.repository.projection.ScheduleParticipantProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScheduleParticipationRepository extends JpaRepository<ScheduleParticipation, Long> {
    Optional<ScheduleParticipation> findByScheduleEntryIdAndUserId(Long scheduleEntryId, Long userId);
    long countByScheduleEntryId(Long scheduleEntryId);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.ScheduleParticipantProjection(
            p.scheduleEntry.id, p.user.id, p.user.username, p.user.role
        )
        from ScheduleParticipation p
        where p.scheduleEntry.id in :entryIds
        order by p.id asc
        """)
    List<ScheduleParticipantProjection> findPublicParticipantRows(@Param("entryIds") Collection<Long> entryIds);
}
