package com.mafia.classicmafia.api.dto.admin;

import java.util.Map;

public record ModeratorOverridesUpdateRequest(Map<String, String> scopes, String currentPassword) {
}
