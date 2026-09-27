package com.mafia.classicmafia.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record ApiPageResponse<T>(
    List<T> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {
    public static <S, T> ApiPageResponse<T> from(Page<S> source, Function<S, T> mapper) {
        return new ApiPageResponse<>(
            source.getContent().stream().map(mapper).toList(),
            source.getNumber(),
            source.getSize(),
            source.getTotalElements(),
            source.getTotalPages()
        );
    }
}
