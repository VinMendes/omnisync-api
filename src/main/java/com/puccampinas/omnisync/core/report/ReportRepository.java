package com.puccampinas.omnisync.core.report;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class ReportRepository {
    private final JdbcClient jdbc;
    public ReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public String companyName(Long tenant) {
        return jdbc.sql("SELECT name FROM system_client WHERE id = :tenant")
                .param("tenant", tenant).query(String.class).optional()
                .orElseThrow(() -> new EntityNotFoundException("Cliente não encontrado."));
    }

    public List<Map<String, Object>> rows(Long tenant, ReportQuery query) {
        String sql = switch (query.type()) {
            case SALES -> """
                SELECT s.id, s.created_at, p.name AS product_name, p.sku, s.quantity,
                       s.total_value, s.channel, s.status, s.external_reference_id
                FROM sales s JOIN products p ON p.id = s.product_id AND p.system_client_id = s.system_client_id
                WHERE s.system_client_id = :tenant
                """;
            case INVENTORY -> """
                SELECT p.id, p.sku, p.name AS product_name, p.stock, p.reserved_stock,
                       p.stock - p.reserved_stock AS available_stock, p.minimum_stock, p.price,
                       p.stock * p.price AS inventory_value
                FROM products p WHERE p.system_client_id = :tenant AND p.active
                """;
            case LISTINGS -> """
                SELECT p.name AS product_name, p.sku, UPPER(listing.key) AS marketplace,
                       listing.value ->> 'item_id' AS external_id,
                       COALESCE(listing.value -> 'raw' ->> 'price', listing.value ->> 'price') AS price,
                       COALESCE(listing.value -> 'raw' ->> 'available_quantity',
                                listing.value ->> 'available_quantity') AS stock,
                       COALESCE(listing.value ->> 'status', listing.value -> 'raw' ->> 'status') AS status
                FROM products p
                CROSS JOIN LATERAL jsonb_each(COALESCE(p.resource, '{}'::jsonb)) listing
                WHERE p.system_client_id = :tenant
                  AND listing.key IN ('mercado_livre', 'shopee', 'amazon')
                  AND NULLIF(BTRIM(listing.value ->> 'item_id'), '') IS NOT NULL
                """;
        };
        String dateColumn = query.type() == ReportType.SALES ? "s.created_at" : "p.created_at";
        if (query.start() != null) sql += " AND " + dateColumn + " >= :start AND " + dateColumn + " < :end";
        if (!query.marketplaces().isEmpty())
            sql += query.type() == ReportType.SALES ? " AND s.channel IN (:marketplaces)" : " AND UPPER(listing.key) IN (:marketplaces)";
        sql += query.type() == ReportType.SALES ? " ORDER BY s.created_at, s.id" : " ORDER BY p.id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", tenant);
        if (query.start() != null) statement = statement.param("start", query.start().atStartOfDay())
                .param("end", query.end().plusDays(1).atStartOfDay());
        if (!query.marketplaces().isEmpty()) statement = statement.param("marketplaces", query.marketplaces());
        return statement.query().listOfRows();
    }
}
