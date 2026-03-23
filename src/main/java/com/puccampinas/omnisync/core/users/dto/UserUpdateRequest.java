package com.puccampinas.omnisync.core.users.dto;

import java.util.Map;

public record UserUpdateRequest(
        String name,
        String email,
        Map<String, Object> resource
) {}