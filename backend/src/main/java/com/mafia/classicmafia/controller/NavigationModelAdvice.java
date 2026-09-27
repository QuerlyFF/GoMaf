package com.mafia.classicmafia.controller;

import com.mafia.classicmafia.service.ModeratorPermissionService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
public class NavigationModelAdvice {
    private final ModeratorPermissionService permissions;

    public NavigationModelAdvice(ModeratorPermissionService permissions) {
        this.permissions = permissions;
    }

    @ModelAttribute("navAuthenticated")
    boolean navAuthenticated(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }

    @ModelAttribute("navAdmin")
    boolean navAdmin(Authentication authentication) {
        if (!navAuthenticated(authentication)) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    @ModelAttribute("navManager")
    boolean navManager(Authentication authentication) {
        if (!navAuthenticated(authentication)) {
            return false;
        }
        return permissions.effectivePermissions(authentication).values().stream().anyMatch(Boolean::booleanValue);
    }

    @ModelAttribute("permissions")
    Map<String, Boolean> permissions(Authentication authentication) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        permissions.effectivePermissions(authentication)
            .forEach((scope, allowed) -> result.put(scope.name(), allowed));
        return result;
    }

}
