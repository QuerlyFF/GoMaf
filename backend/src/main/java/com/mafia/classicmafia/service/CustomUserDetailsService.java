package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.MafiaUserPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public CustomUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var account = users.findLoginAccountByUsernameKey(User.canonicalUsername(username))
            .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден."));
        return new MafiaUserPrincipal(
            account.id(),
            account.username(),
            account.password(),
            account.role().name(),
            account.credentialVersion(),
            account.authorizationVersion()
        );
    }
}
