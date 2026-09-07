package com.puccampinas.omnisync.core.users.enums;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.puccampinas.omnisync.core.users.enums.Permission.*;

/** Códigos estáveis do domínio, independentes dos rótulos apresentados pela interface. */
public enum Permission {
    PRODUCT_READ,
    PRODUCT_WRITE,
    LISTING_PUBLISH,
    SALE_READ,
    SALE_WRITE,
    USER_MANAGE,
    INTEGRATION_MANAGE,
    SETTINGS_MANAGE;

    private static final Map<String, Set<Permission>> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("acesso total", Set.copyOf(EnumSet.allOf(Permission.class))),
            Map.entry("full access", Set.copyOf(EnumSet.allOf(Permission.class))),
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

    private static final List<String> LEGACY_FULL_ACCESS_LABELS = List.of(
            "Acesso total",
            "Gestão de usuários",
            "Faturamento",
            "Gestão de estoque",
            "Anúncios",
            "Vendas",
            "Marketplaces",
            "Atividade",
            "Somente leitura"
    );

    public static Permission parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Permissão desconhecida: " + value);
        }
    }

    public static Set<Permission> parseSet(Object value, boolean strict) {
        if (!(value instanceof List<?> entries)) {
            if (strict) {
                throw new IllegalArgumentException("permissions deve ser uma lista de strings válidas.");
            }
            return Set.of();
        }

        Set<Permission> parsed = EnumSet.noneOf(Permission.class);
        for (Object entry : entries) {
            if (!(entry instanceof String name)) {
                if (strict) {
                    throw new IllegalArgumentException("Cada permission deve ser uma string válida.");
                }
                continue;
            }
            Set<Permission> alias = LEGACY_ALIASES.get(name.trim().toLowerCase(Locale.ROOT));
            if (alias != null) {
                parsed.addAll(alias);
                continue;
            }
            try {
                parsed.add(parse(name));
            } catch (IllegalArgumentException unknown) {
                if (strict) {
                    throw unknown;
                }
            }
        }
        return Set.copyOf(parsed);
    }

    public static List<String> names(Set<Permission> permissions) {
        return permissions.stream().sorted().map(Enum::name).toList();
    }

    /** Rótulos aceitos pelo frontend legado, sem ampliar o conjunto no round-trip. */
    public static List<String> legacyNames(Set<Permission> permissions) {
        if (permissions.equals(Role.ADMIN.defaultPermissions())) {
            return LEGACY_FULL_ACCESS_LABELS;
        }

        Set<Permission> remaining = EnumSet.noneOf(Permission.class);
        remaining.addAll(permissions);
        List<String> labels = new ArrayList<>();
        for (String label : List.of("Gestão de estoque", "Anúncios", "Vendas", "Gestão de usuários",
                "Marketplaces", "Somente leitura", "Faturamento")) {
            Set<Permission> represented = LEGACY_ALIASES.get(label.toLowerCase(Locale.ROOT));
            if (permissions.containsAll(represented) && !Collections.disjoint(remaining, represented)) {
                labels.add(label);
                remaining.removeAll(represented);
            }
        }
        remaining.stream().sorted().map(Enum::name).forEach(labels::add);
        return List.copyOf(labels);
    }
}
