package com.puccampinas.omnisync.core.users.enums;

import java.util.Locale;

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

    public static Permission parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Permissão desconhecida: " + value);
        }
    }
}
