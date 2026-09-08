package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.users.enums.Permission;

/** Nomes estáveis das authorities derivadas das permissões persistidas. */
public final class PermissionAuthority {

    public static final String PREFIX = "PERM_";

    public static final String PRODUCT_READ = PREFIX + "PRODUCT_READ";
    public static final String PRODUCT_WRITE = PREFIX + "PRODUCT_WRITE";
    public static final String LISTING_PUBLISH = PREFIX + "LISTING_PUBLISH";
    public static final String SALE_READ = PREFIX + "SALE_READ";
    public static final String SALE_WRITE = PREFIX + "SALE_WRITE";
    public static final String USER_MANAGE = PREFIX + "USER_MANAGE";
    public static final String INTEGRATION_MANAGE = PREFIX + "INTEGRATION_MANAGE";
    public static final String SETTINGS_MANAGE = PREFIX + "SETTINGS_MANAGE";

    public static final String HAS_PRODUCT_READ = "hasAuthority('" + PRODUCT_READ + "')";
    public static final String HAS_PRODUCT_WRITE = "hasAuthority('" + PRODUCT_WRITE + "')";
    public static final String HAS_LISTING_PUBLISH = "hasAuthority('" + LISTING_PUBLISH + "')";
    public static final String HAS_SALE_READ = "hasAuthority('" + SALE_READ + "')";
    public static final String HAS_SALE_WRITE = "hasAuthority('" + SALE_WRITE + "')";
    public static final String HAS_USER_MANAGE = "hasAuthority('" + USER_MANAGE + "')";
    public static final String HAS_INTEGRATION_MANAGE = "hasAuthority('" + INTEGRATION_MANAGE + "')";
    public static final String HAS_SETTINGS_MANAGE = "hasAuthority('" + SETTINGS_MANAGE + "')";

    private PermissionAuthority() {
    }

    public static String of(Permission permission) {
        return PREFIX + permission.name();
    }
}
