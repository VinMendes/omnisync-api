package com.puccampinas.omnisync.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleConsistencyMigrationIntegrationTest {

    @Test
    void cleanDisposableDatabasePassesReadOnlyPreflight() throws Exception {
        try (EmbeddedPostgres postgres = databaseAtV15()) {
            JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());

            assertThat(count(jdbc, """
                    SELECT COUNT(*) FROM (
                        SELECT system_client_id, channel, BTRIM(external_reference_id)
                          FROM sales
                         WHERE external_reference_id IS NOT NULL
                         GROUP BY system_client_id, channel, BTRIM(external_reference_id)
                        HAVING COUNT(*) > 1
                    ) duplicate_keys
                    """)).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM sales WHERE external_reference_id IS NOT NULL AND BTRIM(external_reference_id) = ''")).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM sales WHERE quantity <= 0 OR total_value < 0")).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM products WHERE stock < 0 OR reserved_stock < 0 OR stock < reserved_stock")).isZero();
        }
    }

    @Test
    void v16NormalizesReferencesAndAddsConstraintsAndDeliveryTables() throws Exception {
        try (EmbeddedPostgres postgres = databaseAtV15()) {
            JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
            long tenant = tenant(jdbc, "clean");
            long product = product(jdbc, tenant, "SKU-CLEAN", 5, 1);
            sale(jdbc, tenant, product, 1, BigDecimal.TEN, "  EXT-1  ");
            sale(jdbc, tenant, product, 1, BigDecimal.ZERO, null);

            migrateToV16(postgres);

            assertThat(jdbc.queryForObject(
                    "SELECT external_reference_id FROM sales WHERE external_reference_id IS NOT NULL",
                    String.class
            )).isEqualTo("EXT-1");
            assertThat(jdbc.queryForObject("SELECT to_regclass('marketplace_stock_sync_outbox')", String.class))
                    .isEqualTo("marketplace_stock_sync_outbox");
            assertThat(jdbc.queryForObject("SELECT to_regclass('marketplace_webhook_inbox')", String.class))
                    .isEqualTo("marketplace_webhook_inbox");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE external_reference_id IS NULL", Long.class))
                    .isEqualTo(1L);

            assertThatThrownBy(() -> sale(jdbc, tenant, product, 1, BigDecimal.ONE, "EXT-1"))
                    .hasMessageContaining("uk_sales_idempotency_key");
            assertThatThrownBy(() -> sale(jdbc, tenant, product, 0, BigDecimal.ONE, "QTY-0"))
                    .hasMessageContaining("ck_sales_quantity_positive");
            assertThatThrownBy(() -> sale(jdbc, tenant, product, 1, BigDecimal.valueOf(-1), "VALUE-NEG"))
                    .hasMessageContaining("ck_sales_total_value_nonnegative");
            assertThatThrownBy(() -> jdbc.update("UPDATE products SET reserved_stock = stock + 1 WHERE id = ?", product))
                    .hasMessageContaining("ck_products_stock_covers_reserved");
        }
    }

    @Test
    void v16AbortsBeforeMutationForDuplicateOrBlankReferences() throws Exception {
        try (EmbeddedPostgres postgres = databaseAtV15()) {
            JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
            long tenant = tenant(jdbc, "unsafe-ref");
            long product = product(jdbc, tenant, "SKU-REF", 5, 0);
            sale(jdbc, tenant, product, 1, BigDecimal.ONE, "DUP");
            sale(jdbc, tenant, product, 1, BigDecimal.ONE, " DUP ");
            sale(jdbc, tenant, product, 1, BigDecimal.ONE, "   ");

            assertThatThrownBy(() -> migrateToV16(postgres))
                    .hasStackTraceContaining("V16 aborted")
                    .hasStackTraceContaining("idempotency");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE external_reference_id = ' DUP '", Long.class))
                    .isEqualTo(1L);
        }
    }

    @Test
    void v16AbortsForInvalidSaleOrProductInvariants() throws Exception {
        try (EmbeddedPostgres postgres = databaseAtV15()) {
            JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
            long tenant = tenant(jdbc, "unsafe-values");
            long product = product(jdbc, tenant, "SKU-BAD", 1, 2);
            sale(jdbc, tenant, product, 0, BigDecimal.valueOf(-1), null);

            assertThatThrownBy(() -> migrateToV16(postgres))
                    .hasStackTraceContaining("V16 aborted")
                    .hasStackTraceContaining("invalid");
        }
    }

    private EmbeddedPostgres databaseAtV15() throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1")
                .start();
        Flyway.configure().dataSource(postgres.getPostgresDatabase()).target("15").load().migrate();
        return postgres;
    }

    private void migrateToV16(EmbeddedPostgres postgres) {
        Flyway.configure().dataSource(postgres.getPostgresDatabase()).target("16").load().migrate();
    }

    private long count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long tenant(JdbcTemplate jdbc, String suffix) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Tenant " + suffix,
                "doc-" + suffix
        );
    }

    private long product(JdbcTemplate jdbc, long tenant, String sku, int stock, int reserved) {
        return jdbc.queryForObject("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    price, resource, active
                ) VALUES (?, ?, 'Product', 'Description', ?, ?, 10.00, '{}'::jsonb, TRUE)
                RETURNING id
                """, Long.class, tenant, sku, stock, reserved);
    }

    private void sale(
            JdbcTemplate jdbc,
            long tenant,
            long product,
            int quantity,
            BigDecimal totalValue,
            String reference
    ) {
        jdbc.update("""
                INSERT INTO sales(
                    system_client_id, product_id, quantity, total_value, resource,
                    channel, external_reference_id, status
                ) VALUES (?, ?, ?, ?, '{}'::jsonb, 'MANUAL', ?, 'CONFIRMED')
                """, tenant, product, quantity, totalValue, reference);
    }
}
