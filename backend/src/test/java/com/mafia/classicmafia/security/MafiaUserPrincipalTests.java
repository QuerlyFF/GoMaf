package com.mafia.classicmafia.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MafiaUserPrincipalTests {
    @Test
    void eraseCredentialsRemovesPasswordHashFromPrincipal() {
        MafiaUserPrincipal principal = new MafiaUserPrincipal(
            42L,
            "player",
            "$2a$12$example-hash",
            "PLAYER",
            3,
            7
        );

        principal.eraseCredentials();

        assertThat(principal.getPassword()).isNull();
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.credentialVersion()).isEqualTo(3);
        assertThat(principal.authorizationVersion()).isEqualTo(7);
    }
}
