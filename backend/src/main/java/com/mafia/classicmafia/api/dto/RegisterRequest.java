package com.mafia.classicmafia.api.dto;

public record RegisterRequest(String username, String password, String passwordConfirm, String captcha) {
}
