package com.mafia.classicmafia;

import com.mafia.classicmafia.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserCanonicalizationTests {
    @Test
    void usernameKeyIsCaseInsensitiveButDisplayNameIsPreserved() {
        User user = new User();
        user.setUsername("  Игрок_Test  ");

        assertThat(user.getUsername()).isEqualTo("  Игрок_Test  ");
        assertThat(user.getUsernameKey()).isEqualTo("игрок_test");
        assertThat(User.canonicalUsername("ИГРОК_TEST")).isEqualTo("игрок_test");
    }
}
