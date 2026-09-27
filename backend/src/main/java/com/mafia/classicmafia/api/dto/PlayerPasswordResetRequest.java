package com.mafia.classicmafia.api.dto;

public record PlayerPasswordResetRequest(String password, String currentPassword) {
}
