package com.mafia.classicmafia.api.dto.admin;

import java.util.List;
import java.util.Map;

public record ModeratorDefaultsResponse(List<ModeratorScopeResponse> scopes, Map<String, Boolean> defaults) {
}
