package com.puccampinas.omnisync.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String message,
        String code,
        Integer retryAfterSeconds,
        Instant lastSyncAt,
        Map<String, Object> details
) {
    public ErrorResponse(int status, String message) {
        this(status, message, null, null, null, null);
    }

    public ErrorResponse(int status, String message, String code, Integer retryAfterSeconds, Instant lastSyncAt) {
        this(status, message, code, retryAfterSeconds, lastSyncAt, null);
    }
}
