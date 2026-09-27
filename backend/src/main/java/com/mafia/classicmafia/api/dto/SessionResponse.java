package com.mafia.classicmafia.api.dto;

import java.util.List;

public record SessionResponse(
    boolean authenticated,
    Long userId,
    String username,
    String role,
    List<String> moderatorPermissions
) {
    public static SessionResponse anonymous() {
        return new SessionResponse(false, null, null, null, List.of());
    }
}
