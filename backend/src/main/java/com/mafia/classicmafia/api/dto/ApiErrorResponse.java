package com.mafia.classicmafia.api.dto;

import java.util.List;

public record ApiErrorResponse(
    int status,
    String code,
    String message,
    List<ApiFieldError> fieldErrors,
    String requestId
) {
    public record ApiFieldError(String field, String code, String message) {
    }
}
