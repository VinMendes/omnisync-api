package com.puccampinas.omnisync.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String message,
        String code,
        Integer retryAfterSeconds,
        Instant lastSyncAt
) {
    public ErrorResponse(String message) {
        this(message, null, null, null);
    }
}
