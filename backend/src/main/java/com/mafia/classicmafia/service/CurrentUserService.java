package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import com.mafia.classicmafia.repository.projection.SelfProfileProjection;
import com.mafia.classicmafia.security.MafiaUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }


    public SecurityAccountProjection requireSecurity(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Пользователь не аутентифицирован.");
        }
        if (authentication.getPrincipal() instanceof MafiaUserPrincipal principal) {
            return users.findSecurityAccountById(principal.userId()).orElseThrow();
        }
        return users.findSecurityAccountByUsernameKey(User.canonicalUsername(authentication.getName())).orElseThrow();
    }

    public SelfProfileProjection requireSelfProfile(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Пользователь не аутентифицирован.");
        }
        if (authentication.getPrincipal() instanceof MafiaUserPrincipal principal) {
            return users.findSelfProfileById(principal.userId()).orElseThrow();
        }
        return users.findSelfProfileByUsernameKey(User.canonicalUsername(authentication.getName())).orElseThrow();
    }

    public User require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Пользователь не аутентифицирован.");
        }
        if (authentication.getPrincipal() instanceof MafiaUserPrincipal principal) {
            return users.findById(principal.userId()).orElseThrow();
        }
        return users.findByUsernameKey(User.canonicalUsername(authentication.getName())).orElseThrow();
    }
}
