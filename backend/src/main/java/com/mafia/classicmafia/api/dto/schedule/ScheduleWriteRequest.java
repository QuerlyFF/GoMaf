package com.mafia.classicmafia.api.dto.schedule;

import java.time.LocalDateTime;

public record ScheduleWriteRequest(
    String entryType,
    String title,
    LocalDateTime scheduledAt,
    String host,
    String description,
    boolean rated,
    String mafiaFormat
) {
}
