package com.mafia.classicmafia.repository.projection;

import com.mafia.classicmafia.entity.Role;

public record LoginAccountProjection(
    Long id,
    String username,
    String password,
    Role role,
    Integer credentialVersion,
    Integer authorizationVersion
) {
}
