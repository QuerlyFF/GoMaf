package com.mafia.classicmafia.api.dto.admin;

import java.util.Map;

public record ModeratorResponse(Long userId, String username, Map<String, ModeratorScopeCellResponse> scopes) {
}
