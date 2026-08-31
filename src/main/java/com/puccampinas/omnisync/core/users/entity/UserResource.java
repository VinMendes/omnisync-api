package com.puccampinas.omnisync.core.users.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.puccampinas.omnisync.core.users.enums.Permission.*;

/**
 * Contrato persistido no JSONB de users, preservando os demais metadados.
 * A leitura legada é tolerante; entradas de API passam por create/update, que são estritos.
 */
public record UserResource(Role role, Set<Permission> permissions, Map<String, Object> attributes) {

    private static final Map<String, Set<Permission>> LEGACY_PERMISSIONS = Map.ofEntries(
            Map.entry("acesso total", Role.ADMIN.defaultPermissions()),
            Map.entry("full access", Role.ADMIN.defaultPermissions()),
            Map.entry("gestão de estoque", Set.of(PRODUCT_READ, PRODUCT_WRITE)),
            Map.entry("stock management", Set.of(PRODUCT_READ, PRODUCT_WRITE)),
            Map.entry("anúncios", Set.of(PRODUCT_READ, LISTING_PUBLISH)),
            Map.entry("listings", Set.of(PRODUCT_READ, LISTING_PUBLISH)),
            Map.entry("vendas", Set.of(SALE_READ, SALE_WRITE)),
            Map.entry("orders", Set.of(SALE_READ, SALE_WRITE)),
            Map.entry("faturamento", Set.of(SALE_READ)),
            Map.entry("billing", Set.of(SALE_READ)),
            Map.entry("gestão de usuários", Set.of(USER_MANAGE)),
            Map.entry("user management", Set.of(USER_MANAGE)),
            Map.entry("marketplaces", Set.of(INTEGRATION_MANAGE)),
            Map.entry("atividade", Set.of(PRODUCT_READ, SALE_READ)),
            Map.entry("activity", Set.of(PRODUCT_READ, SALE_READ)),
            Map.entry("somente leitura", Set.of(PRODUCT_READ, SALE_READ)),
            Map.entry("view only", Set.of(PRODUCT_READ, SALE_READ))
    );

    public UserResource {
        role = role == null ? Role.VIEWER : role;
        permissions = permissions == null ? role.defaultPermissions() : Set.copyOf(permissions);
        Map<String, Object> metadata = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        metadata.remove("role");
        metadata.remove("permissions");
        attributes = Collections.unmodifiableMap(metadata);
    }

    public static UserResource defaults() {
        return new UserResource(Role.VIEWER, null, Map.of());
    }

    public static UserResource create(Map<String, Object> resource, String role, List<String> permissions) {
        return apply(defaults(), resource, role, permissions, true);
    }

    public UserResource update(Map<String, Object> resource, String role, List<String> permissions) {
        return apply(this, resource, role, permissions, false);
    }

    private static UserResource apply(UserResource current, Map<String, Object> resource,
                                      String role, List<String> permissions, boolean creating) {
        Map<String, Object> input = resource == null ? Map.of() : resource;
        Role nestedRole = input.get("role") == null ? null : parseRole(input.get("role"));
        Role explicitRole = role == null ? null : Role.parse(role);
        if (nestedRole != null && explicitRole != null && nestedRole != explicitRole) {
            throw new IllegalArgumentException("role e resource.role devem informar o mesmo papel.");
        }
        Role selectedRole = explicitRole != null ? explicitRole : nestedRole != null ? nestedRole : current.role();
        Set<Permission> nestedPermissions = input.get("permissions") == null ? null : parsePermissions(input.get("permissions"), true);
        Set<Permission> explicitPermissions = permissions == null ? null : parsePermissions(permissions, true);
        if (nestedPermissions != null && explicitPermissions != null && !nestedPermissions.equals(explicitPermissions)) {
            throw new IllegalArgumentException("permissions e resource.permissions devem informar as mesmas permissões.");
        }
        Set<Permission> selectedPermissions = explicitPermissions != null ? explicitPermissions : nestedPermissions;
        if (selectedPermissions == null) {
            selectedPermissions = creating || selectedRole != current.role()
                    ? selectedRole.defaultPermissions() : current.permissions();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(current.attributes());
        metadata.putAll(input);
        return new UserResource(selectedRole, selectedPermissions, metadata);
    }

    /** Usado por Hibernate/Jackson: JSON legado desconhecido nunca concede authorities arbitrárias. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserResource fromStoredJson(Object raw) {
        if (!(raw instanceof Map<?, ?> input)) {
            return new UserResource(Role.VIEWER, null, raw == null ? Map.of() : Map.of("_legacy_resource", raw));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key instanceof String name) metadata.put(name, value);
        });
        if (input.get("role") == null) {
            return new UserResource(Role.VIEWER, null, metadata);
        }
        Role selectedRole;
        try {
            selectedRole = parseRole(input.get("role"));
        } catch (IllegalArgumentException ignored) {
            return new UserResource(Role.VIEWER, null, metadata);
        }
        Set<Permission> selectedPermissions = input.get("permissions") == null
                ? selectedRole.defaultPermissions() : parsePermissions(input.get("permissions"), false);
        return new UserResource(selectedRole, selectedPermissions, metadata);
    }

    private static Role parseRole(Object value) {
        if (!(value instanceof String name)) {
            throw new IllegalArgumentException("role deve ser uma string: ADMIN, MANAGER, SELLER ou VIEWER.");
        }
        return Role.parse(name);
    }

    private static Set<Permission> parsePermissions(Object value, boolean strict) {
        if (!(value instanceof List<?> entries)) {
            if (strict) throw new IllegalArgumentException("permissions deve ser uma lista de strings válidas.");
            return Set.of();
        }
        Set<Permission> parsed = EnumSet.noneOf(Permission.class);
        for (Object entry : entries) {
            if (!(entry instanceof String name)) {
                if (strict) throw new IllegalArgumentException("Cada permission deve ser uma string válida.");
                continue;
            }
            Set<Permission> legacy = LEGACY_PERMISSIONS.get(name.trim().toLowerCase(Locale.ROOT));
            if (legacy != null) {
                parsed.addAll(legacy);
                continue;
            }
            try {
                parsed.add(Permission.parse(name));
            } catch (IllegalArgumentException unknown) {
                if (strict) throw unknown;
            }
        }
        return Set.copyOf(parsed);
    }

    public List<String> permissionNames() {
        return permissions.stream().sorted().map(Enum::name).toList();
    }

    /** Formato canônico gravado no banco. As anotações funcionam com Jackson 2 (Hibernate) e Jackson 3 (MVC). */
    @JsonValue
    public Map<String, Object> toStoredJson() {
        Map<String, Object> json = new LinkedHashMap<>(attributes);
        json.put("role", role.name());
        json.put("permissions", permissionNames());
        return json;
    }

    /** Projeção de compatibilidade: os campos explícitos de UserResponse continuam sendo os canônicos. */
    public Map<String, Object> toLegacyJson() {
        Map<String, Object> json = new LinkedHashMap<>(attributes);
        json.put("role", role.legacyName());
        json.put("permissions", legacyPermissionNames());
        return json;
    }

    private List<String> legacyPermissionNames() {
        if (permissions.equals(Role.ADMIN.defaultPermissions())) {
            return List.of("Acesso total", "Faturamento", "Gestão de usuários");
        }
        Set<Permission> remaining = EnumSet.noneOf(Permission.class);
        remaining.addAll(permissions);
        List<String> labels = new ArrayList<>();
        for (String label : List.of("Gestão de estoque", "Anúncios", "Vendas", "Gestão de usuários",
                "Marketplaces", "Somente leitura", "Faturamento")) {
            Set<Permission> represented = LEGACY_PERMISSIONS.get(label.toLowerCase(Locale.ROOT));
            if (permissions.containsAll(represented) && !Collections.disjoint(remaining, represented)) {
                labels.add(label);
                remaining.removeAll(represented);
            }
        }
        // Não converter um subconjunto em rótulo mais amplo: o round-trip da UI não pode ampliar permissões.
        remaining.stream().sorted().map(Enum::name).forEach(labels::add);
        return List.copyOf(labels);
    }
}
