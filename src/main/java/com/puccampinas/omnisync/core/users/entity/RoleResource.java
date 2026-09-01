package com.puccampinas.omnisync.core.users.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.puccampinas.omnisync.core.users.enums.Permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Conteúdo tipado de {@code roles.resource}; as permissões pertencem à configuração do usuário. */
public record RoleResource(Set<Permission> permissions, Map<String, Object> attributes) {

    public RoleResource {
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
        Map<String, Object> metadata = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        metadata.remove("permissions");
        attributes = Collections.unmodifiableMap(metadata);
    }

    public static RoleResource of(Set<Permission> permissions) {
        return new RoleResource(permissions, Map.of());
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RoleResource fromStoredJson(Object raw) {
        if (!(raw instanceof Map<?, ?> input)) {
            return new RoleResource(Set.of(), raw == null ? Map.of() : Map.of("_legacy_resource", raw));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key instanceof String name) {
                metadata.put(name, value);
            }
        });
        Set<Permission> permissions = input.get("permissions") == null
                ? Set.of() : Permission.parseSet(input.get("permissions"), false);
        return new RoleResource(permissions, metadata);
    }

    @JsonValue
    public Map<String, Object> toStoredJson() {
        Map<String, Object> json = new LinkedHashMap<>(attributes);
        json.put("permissions", Permission.names(permissions));
        return json;
    }
}
