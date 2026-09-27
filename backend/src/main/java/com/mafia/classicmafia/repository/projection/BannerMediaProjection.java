package com.mafia.classicmafia.repository.projection;

public record BannerMediaProjection(
    byte[] data,
    String contentType,
    Integer safetyVersion
) {
}
