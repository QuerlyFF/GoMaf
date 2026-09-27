package com.mafia.classicmafia.api.dto.schedule;

import java.time.LocalDateTime;
import java.util.List;

public record ScheduleEntryResponse(
    Long id,
    String entryType,
    String title,
    LocalDateTime scheduledAt,
    String host,
    String description,
    boolean rated,
    String mafiaFormat,
    String mafiaFormatDisplayName,
    List<ScheduleParticipantResponse> participants,
    boolean participating,
    boolean manageable
) {
}
