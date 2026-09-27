package com.mafia.classicmafia.api.dto;

public record CsrfResponse(String headerName, String parameterName, String token) {
}
