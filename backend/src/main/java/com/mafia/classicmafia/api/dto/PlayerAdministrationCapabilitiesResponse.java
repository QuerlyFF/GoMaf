package com.mafia.classicmafia.api.dto;

public record PlayerAdministrationCapabilitiesResponse(
    boolean manageable,
    boolean rating,
    boolean username,
    boolean password,
    boolean avatarReset,
    boolean profileEdit,
    boolean deleteAccount,
    boolean sensitiveActionsRequireCurrentPassword
) {
}
