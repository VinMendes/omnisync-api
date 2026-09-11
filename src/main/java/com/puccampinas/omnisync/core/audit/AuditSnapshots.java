package com.puccampinas.omnisync.core.audit;

import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit allowlists. Never serialize entities, arbitrary resource, request or provider payloads. */
public final class AuditSnapshots {
    private AuditSnapshots() {}

    public static Map<String, Object> user(User user) {
        return fields("name", user.getName(), "email", user.getEmail(), "active", user.getActive(),
                "role", user.getTenantRole().getName().name(),
                "permissions", user.getTenantRole().permissionNames());
    }

    public static Map<String, Object> product(Product product) {
        return fields("sku", product.getSku(), "name", product.getName(), "description", product.getDescription(), "stock", product.getStock(),
                "reserved_stock", product.getReservedStock(), "price", product.getPrice(), "active", product.getActive());
    }

    public static Map<String, Object> sale(Sale sale) {
        return fields("product_id", sale.getProductId(), "quantity", sale.getQuantity(),
                "total_value", sale.getTotalValue() == null ? null : sale.getTotalValue().stripTrailingZeros(),
                "channel", sale.getChannel(), "status", sale.getStatus());
    }

    public static Map<String, Object> integration(MarketplaceIntegration integration) {
        return integration.getId() == null ? null :
                fields("marketplace", integration.getMarketplace().name(), "active", integration.getActive(),
                        "last_sync_at", integration.getLastSyncAt() == null ? null : integration.getLastSyncAt().toString());
    }

    public static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
