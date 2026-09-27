package com.mafia.classicmafia.api.dto.content;

import java.time.LocalDateTime;

public record NewsResponse(Long id, String title, String content, LocalDateTime createdAt, boolean manageable) {
}
