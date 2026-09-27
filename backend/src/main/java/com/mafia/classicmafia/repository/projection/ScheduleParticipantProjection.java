package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.Role;

public record ScheduleParticipantProjection(
    Long scheduleEntryId,
    Long userId,
    String username,
    Role role
) {
}
