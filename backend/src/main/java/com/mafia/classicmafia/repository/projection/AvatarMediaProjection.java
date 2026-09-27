package com.mafia.classicmafia.repository.projection;

public record AvatarMediaProjection(
    byte[] data,
    String contentType,
    Integer safetyVersion
) {
}
