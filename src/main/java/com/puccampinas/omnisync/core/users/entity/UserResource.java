package com.puccampinas.omnisync.core.users.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Características próprias do usuário persistidas em {@code users.resource}.
 * Papel e permissões são removidos desse JSON e vêm da relação com {@code roles}.
 */
public record UserResource(Map<String, Object> attributes) {

    public UserResource {
        Map<String, Object> sanitized = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        sanitized.remove("role");
        sanitized.remove("permissions");
        attributes = Collections.unmodifiableMap(sanitized);
    }

    public static UserResource defaults() {
        return new UserResource(Map.of());
    }

    public static UserResource create(Map<String, Object> resource) {
        return new UserResource(resource);
    }

    public UserResource update(Map<String, Object> resource) {
        if (resource == null) {
            return this;
        }
        Map<String, Object> updated = new LinkedHashMap<>(attributes);
        updated.putAll(resource);
        return new UserResource(updated);
    }

    /** JSON legado malformado é preservado como metadado e nunca concede acesso. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserResource fromStoredJson(Object raw) {
        if (!(raw instanceof Map<?, ?> input)) {
            return new UserResource(raw == null ? Map.of() : Map.of("_legacy_resource", raw));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key instanceof String name) {
                metadata.put(name, value);
            }
        });
        return new UserResource(metadata);
    }

    @JsonValue
    public Map<String, Object> toStoredJson() {
        return attributes;
    }

    /** Projeção temporária para o contrato esperado pelo frontend atual. */
    public Map<String, Object> toLegacyJson(Role role, Set<Permission> permissions) {
        Map<String, Object> json = new LinkedHashMap<>(attributes);
        json.put("role", role.legacyName());
        json.put("permissions", Permission.legacyNames(permissions));
        return json;
    }
}
