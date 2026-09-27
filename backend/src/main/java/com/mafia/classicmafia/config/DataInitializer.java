package com.mafia.classicmafia.config;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.RulesImportState;
import com.mafia.classicmafia.entity.RulesPage;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.RulesImportStateRepository;
import com.mafia.classicmafia.repository.RulesPageRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.PasswordPolicy;
import com.mafia.classicmafia.service.ModeratorAdministrationService;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "runtime", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final int RULES_IMPORT_VERSION = 1;
    private static final String RULES_TITLE = "Официальные правила интеллектуально-спортивной игры «Мафия» Cristalix";
    private static final Set<String> WEAK_PRODUCTION_PASSWORDS = Set.of(
        "test", "admin", "password", "12345678", "qwerty123"
    );

    private final UserRepository users;
    private final RulesPageRepository rules;
    private final RulesImportStateRepository rulesImportState;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final ModeratorAdministrationService moderatorAdministrationService;

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:test}")
    private String adminPassword;

    @Value("${app.environment:local}")
    private String environment;

    public DataInitializer(
        UserRepository users,
        RulesPageRepository rules,
        RulesImportStateRepository rulesImportState,
        PasswordEncoder passwordEncoder,
        PasswordPolicy passwordPolicy,
        ModeratorAdministrationService moderatorAdministrationService
    ) {
        this.users = users;
        this.rules = rules;
        this.rulesImportState = rulesImportState;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.moderatorAdministrationService = moderatorAdministrationService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        backfillCanonicalAndCompatibilityValues();
        importOfficialRulesOnce();
        moderatorAdministrationService.ensureDefaultRows();
        createOrSynchronizeAdmin();
    }

    private void backfillCanonicalAndCompatibilityValues() {
        var accounts = users.findAll();
        Map<String, Long> seen = new HashMap<>();
        boolean changed = false;

        for (User account : accounts) {
            String key = User.canonicalUsername(account.getUsername());
            if (key.isBlank()) {
                throw new IllegalStateException("В базе найден пользователь с пустым ником.");
            }
            Long previous = seen.putIfAbsent(key, account.getId());
            if (previous != null && !previous.equals(account.getId())) {
                throw new IllegalStateException(
                    "В базе есть два ника, совпадающих без учёта регистра: " + account.getUsername()
                );
            }
            if (!key.equals(account.getUsernameKey())) {
                account.setUsernameKey(key);
                changed = true;
            }
            if (account.getCredentialVersion() < 0 || account.getAuthorizationVersion() < 0) {
                throw new IllegalStateException("В базе найден пользователь с некорректной версией безопасности.");
            }
        }

        if (changed) {
            users.saveAllAndFlush(accounts);
            log.info("Заполнены канонические ключи имён пользователей.");
        }
    }

    private void importOfficialRulesOnce() {
        RulesPage page = rules.findById(1L).orElseGet(RulesPage::new);
        RulesImportState state = rulesImportState.findById(1L).orElseGet(RulesImportState::new);

        if (state.getVersion() >= RULES_IMPORT_VERSION) {
            if (page.getId() == null) {
                rules.save(page);
            }
            return;
        }

        page.setTitle(RULES_TITLE);
        page.setContent(loadDefaultRules());
        rules.save(page);

        state.setVersion(RULES_IMPORT_VERSION);
        rulesImportState.save(state);
        log.info("Импортирована официальная редакция правил Cristalix, версия импорта {}.", RULES_IMPORT_VERSION);
    }

    private String loadDefaultRules() {
        try {
            ClassPathResource resource = new ClassPathResource("default-rules.txt");
            return resource.getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить встроенную редакцию правил.", exception);
        }
    }

    private void createOrSynchronizeAdmin() {
        String username = adminUsername == null ? "" : adminUsername.trim();
        String password = adminPassword == null ? "" : adminPassword;

        if (username.isBlank() || password.isBlank()) {
            log.warn("Главный ADMIN не создан: APP_ADMIN_USERNAME или APP_ADMIN_PASSWORD не задан.");
            return;
        }
        if (!"admin".equals(username)) {
            throw new IllegalStateException("APP_ADMIN_USERNAME должен быть строго admin.");
        }

        if ("production".equalsIgnoreCase(environment)) {
            String policyError = passwordPolicy.validate(password);
            if (policyError != null || WEAK_PRODUCTION_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("APP_ADMIN_PASSWORD не проходит production-политику паролей.");
            }
        }

        var existing = users.findByUsernameKey("admin").orElse(null);
        if (existing == null) {
            if (users.countByRole(Role.ADMIN) != 0) {
                throw new IllegalStateException("Найден ADMIN с именем, отличным от admin.");
            }
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(password));
            admin.setRole(Role.ADMIN);
            users.saveAndFlush(admin);
            log.info("Создан единственный главный ADMIN: admin");
            return;
        }

        if (existing.getRole() != Role.ADMIN) {
            throw new IllegalStateException("Зарезервированный username admin занят не-ADMIN аккаунтом.");
        }
        if (users.countByRole(Role.ADMIN) != 1) {
            throw new IllegalStateException("Должен существовать ровно один ADMIN с username admin.");
        }

        if (!"production".equalsIgnoreCase(environment)) {
            return;
        }

        if (!passwordEncoder.matches(password, existing.getPassword())) {
            existing.setPassword(passwordEncoder.encode(password));
            existing.incrementCredentialVersion();
            users.saveAndFlush(existing);
            log.info("Production ADMIN admin синхронизирован с /etc/gomaf/production.env.");
        }
    }
}
