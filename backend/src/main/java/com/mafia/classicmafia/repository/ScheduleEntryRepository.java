package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.ScheduleEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {
    List<ScheduleEntry> findAllByOrderByScheduledAtAsc();

    List<ScheduleEntry> findAllByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
        LocalDateTime from,
        LocalDateTime to
    );
}
