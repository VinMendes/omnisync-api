package com.puccampinas.omnisync.core.users.dto;

import java.util.Map;
import java.util.List;

public record UserUpdateRequest(
        String name,
        String email,
        Map<String, Object> resource,
        String role,
        List<String> permissions
) {
    public UserUpdateRequest(String name, String email, Map<String, Object> resource) {
        this(name, email, resource, null, null);
    }
}
