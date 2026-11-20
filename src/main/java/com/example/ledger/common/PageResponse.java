package com.example.ledger.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paginated lists. We do not serialize Spring Data's {@code Page}
 * directly because its JSON layout is an implementation detail that can change between
 * versions; an API contract should be a type we own.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
