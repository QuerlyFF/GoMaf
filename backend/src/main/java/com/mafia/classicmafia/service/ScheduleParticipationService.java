package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.ScheduleEntry;
import com.mafia.classicmafia.entity.ScheduleParticipation;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.ScheduleEntryRepository;
import com.mafia.classicmafia.repository.ScheduleParticipationRepository;
import com.mafia.classicmafia.security.DatabaseLockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleParticipationService {
    private final ScheduleEntryRepository schedule;
    private final ScheduleParticipationRepository participants;
    private final DatabaseLockService databaseLocks;

    public ScheduleParticipationService(
        ScheduleEntryRepository schedule,
        ScheduleParticipationRepository participants,
        DatabaseLockService databaseLocks
    ) {
        this.schedule = schedule;
        this.participants = participants;
        this.databaseLocks = databaseLocks;
    }

    @Transactional
    public boolean toggle(Long scheduleId, User user) {
        databaseLocks.lock("schedule-participant", scheduleId + ":" + user.getId());
        ScheduleEntry entry = schedule.findById(scheduleId)
            .orElseThrow(() -> new IllegalArgumentException("Запись расписания не найдена."));
        if (!entry.isGame()) {
            throw new IllegalArgumentException("Участвовать можно только в игре.");
        }
        var existing = participants.findByScheduleEntryIdAndUserId(scheduleId, user.getId());
        if (existing.isPresent()) {
            Long participantId = existing.get().getId();
            entry.getParticipants().removeIf(candidate -> candidate.getId().equals(participantId));
            schedule.saveAndFlush(entry);
            return false;
        }
        ScheduleParticipation item = new ScheduleParticipation();
        item.setScheduleEntry(entry);
        item.setUser(user);
        entry.getParticipants().add(item);
        schedule.saveAndFlush(entry);
        return true;
    }
}
