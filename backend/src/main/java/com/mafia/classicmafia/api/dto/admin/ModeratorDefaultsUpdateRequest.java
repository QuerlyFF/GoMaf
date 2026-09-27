package com.mafia.classicmafia.api.dto.admin;

import java.util.Map;

public record ModeratorDefaultsUpdateRequest(Map<String, Boolean> defaults, String currentPassword) {
}
