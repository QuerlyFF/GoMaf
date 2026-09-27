package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.Role;

public record SecurityAccountProjection(
    Long id,
    String username,
    Role role,
    Integer credentialVersion,
    Integer authorizationVersion
) {
}
