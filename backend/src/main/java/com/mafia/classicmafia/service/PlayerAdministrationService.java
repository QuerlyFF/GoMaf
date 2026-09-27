package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.GamePlayerRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.security.PasswordPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayerAdministrationService {
    private static final String USERNAME_PATTERN = "[A-Za-zА-Яа-яЁё0-9_]{3,32}";
    private static final int ABOUT_MAX_LENGTH = 500;

    private final UserRepository users;
    private final GamePlayerRepository gamePlayers;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;

    public PlayerAdministrationService(
        UserRepository users,
        GamePlayerRepository gamePlayers,
        PasswordPolicy passwordPolicy,
        PasswordEncoder passwordEncoder
    ) {
        this.users = users;
        this.gamePlayers = gamePlayers;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User updateRating(User operator, Long userId, double rating) {
        if (!Double.isFinite(rating)) {
            throw validation("Некорректное значение рейтинга.");
        }
        User player = requireManageableTarget(operator, userId);
        player.setRating(rating);
        return users.save(player);
    }

    @Transactional
    public RenameResult rename(User operator, Long userId, String requestedUsername) {
        User player = requireManageableTarget(operator, userId);
        String normalized = requestedUsername == null ? "" : requestedUsername.strip();
        if (!normalized.matches(USERNAME_PATTERN)) {
            throw validation("Ник: 3-32 символа, буквы, цифры и _.");
        }

        String canonical = User.canonicalUsername(normalized);
        if ("admin".equals(canonical)) {
            throw validation("Ник admin зарезервирован системой.");
        }
        users.findByUsernameKey(canonical)
            .filter(existing -> !existing.getId().equals(player.getId()))
            .ifPresent(existing -> {
                throw conflict("Такой ник уже занят.");
            });

        String oldUsername = player.getUsername();
        if (oldUsername.equals(normalized)) {
            return new RenameResult(oldUsername, normalized, false);
        }

        player.setUsername(normalized);
        player.incrementCredentialVersion();
        try {
            users.saveAndFlush(player);
        } catch (DataIntegrityViolationException exception) {
            throw new PlayerAdministrationException(Reason.CONFLICT, "Такой ник уже занят.", exception);
        }
        return new RenameResult(oldUsername, normalized, true);
    }

    @Transactional
    public PasswordResetResult resetPassword(User operator, Long userId, String requestedPassword) {
        User player = requireManageableTarget(operator, userId);
        String passwordError = passwordPolicy.validate(requestedPassword);
        if (passwordError != null) {
            throw validation(passwordError);
        }
        player.setPassword(passwordEncoder.encode(requestedPassword));
        player.incrementCredentialVersion();
        users.saveAndFlush(player);
        return new PasswordResetResult(player.getUsername(), true);
    }

    @Transactional
    public String resetAvatar(User operator, Long userId) {
        User player = requireManageableTarget(operator, userId);
        player.setAvatarData(null);
        player.setAvatarContentType(null);
        player.setAvatarSafetyVersion(null);
        users.save(player);
        return player.getUsername();
    }

    @Transactional
    public User updateAboutAsAdmin(User operator, Long userId, String aboutMe) {
        if (operator == null || operator.getRole() != Role.ADMIN) {
            throw forbidden("Недостаточно прав.");
        }
        User player = requirePublicTarget(userId);
        String normalized = aboutMe == null ? "" : aboutMe.strip();
        if (normalized.length() > ABOUT_MAX_LENGTH) {
            throw validation("Текст о себе должен быть не длиннее 500 символов.");
        }
        player.setAboutMe(normalized);
        return users.save(player);
    }

    @Transactional
    public DeleteResult delete(User operator, Long userId, String expectedUsername) {
        User player = requireManageableTarget(operator, userId);
        String username = player.getUsername();
        if (expectedUsername == null || !username.equals(expectedUsername.strip())) {
            throw validation("Для удаления введите текущий ник игрока точно как он указан.");
        }
        int detachedHistoryRows = gamePlayers.detachUserFromHistory(player.getId());
        users.delete(player);
        users.flush();
        return new DeleteResult(username, detachedHistoryRows);
    }

    @Transactional(readOnly = true)
    public User requireManageableTarget(User operator, Long userId) {
        if (operator == null || operator.getId() == null) {
            throw forbidden("Оператор не найден.");
        }
        User target = requirePublicTarget(userId);

        if (operator.getRole() == Role.ADMIN) {
            return target;
        }
        if (operator.getRole() != Role.MODERATOR) {
            throw forbidden("Недостаточно прав.");
        }
        if (operator.getId().equals(target.getId())) {
            throw forbidden("Модератор не может применять административное действие к собственному аккаунту.");
        }
        if (target.getRole() != Role.PLAYER) {
            throw forbidden("Модератор может управлять только обычными игроками.");
        }
        return target;
    }

    @Transactional(readOnly = true)
    public User requirePublicTarget(Long userId) {
        if (userId == null) {
            throw notFound();
        }
        return users.findById(userId)
            .filter(user -> user.getRole().isPublicPlayer())
            .orElseThrow(this::notFound);
    }

    private PlayerAdministrationException validation(String message) {
        return new PlayerAdministrationException(Reason.VALIDATION, message);
    }

    private PlayerAdministrationException conflict(String message) {
        return new PlayerAdministrationException(Reason.CONFLICT, message);
    }

    private PlayerAdministrationException forbidden(String message) {
        return new PlayerAdministrationException(Reason.FORBIDDEN, message);
    }

    private PlayerAdministrationException notFound() {
        return new PlayerAdministrationException(Reason.NOT_FOUND, "Игрок не найден.");
    }

    public record RenameResult(String oldUsername, String newUsername, boolean changed) {
    }

    public record PasswordResetResult(String username, boolean sessionInvalidated) {
    }

    public record DeleteResult(String username, int detachedHistoryRows) {
    }

    public enum Reason {
        VALIDATION,
        CONFLICT,
        FORBIDDEN,
        NOT_FOUND
    }

    public static class PlayerAdministrationException extends RuntimeException {
        private final Reason reason;

        public PlayerAdministrationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public PlayerAdministrationException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
