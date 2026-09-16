package com.puccampinas.omnisync.core.report;

import com.puccampinas.omnisync.core.users.enums.Permission;
import java.util.LinkedHashMap;
import java.util.Map;

public enum ReportType {
    SALES("Vendas", "vendas", Permission.SALE_READ,
            "id", "Identificador", "created_at", "Data", "product_name", "Produto", "sku", "SKU",
            "quantity", "Quantidade", "total_value", "Valor total", "channel", "Canal",
            "status", "Status", "external_reference_id", "Referência externa"),
    INVENTORY("Estoque", "estoque", Permission.PRODUCT_READ,
            "id", "Identificador", "sku", "SKU", "product_name", "Produto", "stock", "Estoque físico",
            "reserved_stock", "Reservado", "available_stock", "Disponível", "minimum_stock", "Estoque mínimo",
            "price", "Preço", "inventory_value", "Valor em estoque"),
    LISTINGS("Anúncios", "anuncios", Permission.PRODUCT_READ,
            "product_name", "Produto", "sku", "SKU", "marketplace", "Marketplace",
            "external_id", "ID externo", "price", "Preço", "stock", "Quantidade", "status", "Situação");

    private final String title;
    private final String filename;
    private final Permission permission;
    private final Map<String, String> fields;

    ReportType(String title, String filename, Permission permission, String... columns) {
        this.title = title;
        this.filename = filename;
        this.permission = permission;
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i += 2) labels.put(columns[i], columns[i + 1]);
        this.fields = java.util.Collections.unmodifiableMap(labels);
    }

    public String title() { return title; }
    public String filename() { return filename; }
    public Permission permission() { return permission; }
    public Map<String, String> fields() { return fields; }
}
