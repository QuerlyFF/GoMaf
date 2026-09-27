package com.mafia.classicmafia.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PasswordPolicy {
    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 64;

    public String validate(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return "Пароль должен содержать от 6 до 64 символов.";
        }
        if (!isVerificationInputAcceptable(password)) {
            return "Пароль слишком длинный для безопасного хеширования BCrypt.";
        }
        return null;
    }

    public boolean isVerificationInputAcceptable(String password) {
        return password != null
            && password.length() <= MAX_LENGTH
            && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
