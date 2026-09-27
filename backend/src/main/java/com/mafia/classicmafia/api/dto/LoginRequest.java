package com.mafia.classicmafia.api.dto;

public record LoginRequest(String username, String password, String captcha) {
}
