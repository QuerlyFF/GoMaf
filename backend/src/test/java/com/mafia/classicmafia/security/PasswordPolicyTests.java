package com.mafia.classicmafia.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTests {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPassphraseAndRejectsShortOrBcryptOverflow() {
        assertThat(policy.validate("correct-horse-42")).isNull();
        assertThat(policy.validate("x".repeat(5))).contains("6");
        assertThat(policy.validate("я".repeat(40))).contains("BCrypt");
    }
}
