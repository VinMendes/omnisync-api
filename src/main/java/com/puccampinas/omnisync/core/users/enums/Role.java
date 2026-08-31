package com.puccampinas.omnisync.core.users.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.puccampinas.omnisync.core.users.enums.Permission.*;

public enum Role {
    ADMIN,
    MANAGER,
    SELLER,
    VIEWER;

    public static final Map<Role, Set<Permission>> ROLE_DEFAULT_PERMISSIONS = Map.of(
            ADMIN, Set.copyOf(EnumSet.allOf(Permission.class)),
            MANAGER, Set.of(PRODUCT_READ, PRODUCT_WRITE, LISTING_PUBLISH, SALE_READ, SALE_WRITE),
            SELLER, Set.of(PRODUCT_READ, LISTING_PUBLISH, SALE_READ, SALE_WRITE),
            VIEWER, Set.of(PRODUCT_READ, SALE_READ)
    );

    public Set<Permission> defaultPermissions() {
        return ROLE_DEFAULT_PERMISSIONS.get(this);
    }

    public static Role parse(String value) {
        try {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            // Compatibilidade de nomenclatura com o frontend atual. O valor persistido é sempre SELLER.
            return normalized.equals("EDITOR") ? SELLER : valueOf(normalized);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Role desconhecida: " + value
                    + ". Valores permitidos: ADMIN, MANAGER, SELLER, VIEWER.");
        }
    }

    public String legacyName() {
        return this == SELLER ? "editor" : name().toLowerCase(Locale.ROOT);
    }
}
