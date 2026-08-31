package com.puccampinas.omnisync.core.users.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record UserResponse(
        Long id,
        Long systemClientId,
        String name,
        String email,
        Map<String, Object> resource,
        Boolean active,
        LocalDateTime createdAt,
        String role,
        List<String> permissions
) {}
