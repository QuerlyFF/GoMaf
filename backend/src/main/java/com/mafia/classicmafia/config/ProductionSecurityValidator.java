package com.mafia.classicmafia.config;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.PasswordPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "runtime", matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProductionSecurityValidator implements CommandLineRunner {
    private static final Set<String> WEAK_PASSWORDS = Set.of(
        "test", "admin", "password", "12345678", "qwerty123"
    );

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    @Value("${app.environment:local}") private String environment;
    @Value("${app.admin.username:}") private String adminUsername;
    @Value("${app.admin.password:}") private String adminPassword;
    @Value("${app.security.ip-hash-secret:}") private String ipHashSecret;
    @Value("${spring.datasource.password:}") private String datasourcePassword;
    @Value("${server.servlet.session.cookie.secure:false}") private boolean secureCookie;

    public ProductionSecurityValidator(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    public void run(String... args) {
        if (!"production".equalsIgnoreCase(environment)) {
            return;
        }

        String username = adminUsername == null ? "" : adminUsername.trim();
        String password = adminPassword == null ? "" : adminPassword;
        if (!"admin".equals(username)) {
            throw new IllegalStateException("В production APP_ADMIN_USERNAME должен быть строго admin.");
        }
        if (passwordPolicy.validate(password) != null || WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("В production APP_ADMIN_PASSWORD должен проходить политику 12-64 символов/72 UTF-8 байта и не быть тестовым.");
        }
        if (ipHashSecret == null || ipHashSecret.length() < 32) {
            throw new IllegalStateException("В production APP_SECURITY_IP_HASH_SECRET должен содержать минимум 32 символа.");
        }
        if (datasourcePassword == null || datasourcePassword.length() < 32) {
            throw new IllegalStateException("В production пароль пользователя базы приложения должен содержать минимум 32 символа.");
        }
        if (!secureCookie) {
            throw new IllegalStateException("В production SESSION_COOKIE_SECURE должен быть true.");
        }

        var admin = users.findByUsernameKey("admin")
            .orElseThrow(() -> new IllegalStateException("В production ADMIN admin не найден в базе."));
        if (admin.getRole() != Role.ADMIN || users.countByRole(Role.ADMIN) != 1) {
            throw new IllegalStateException("В production должен существовать ровно один ADMIN с username admin.");
        }
        if (!passwordEncoder.matches(password, admin.getPassword())) {
            throw new IllegalStateException("В production пароль ADMIN в базе не соответствует APP_ADMIN_PASSWORD.");
        }
        for (String weak : WEAK_PASSWORDS) {
            if (passwordEncoder.matches(weak, admin.getPassword())) {
                throw new IllegalStateException("В production фактический пароль ADMIN в базе всё ещё является тестовым или слабым.");
            }
        }
    }
}
