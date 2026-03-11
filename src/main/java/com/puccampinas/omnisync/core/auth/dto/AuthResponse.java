package com.puccampinas.omnisync.core.auth.dto;

public record AuthResponse(
        String message,
        Long userId,
        String name,
        String email,
        Boolean active
) {
}