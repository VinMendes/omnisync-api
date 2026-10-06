package com.puccampinas.omnisync.integration;

import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "marketplace.delivery.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class SaleDeploymentRollbackRehearsalIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void rollbackGuardBlocksOldSaleWritesWhileV16DeliveryDataRemainsAvailable() {
        long tenant = jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES ('Rollback rehearsal','roll-rehearsal') RETURNING id",
                Long.class);
        long product = jdbc.queryForObject("""
                INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                     minimum_stock, price, resource, active)
                VALUES (?, 'ROLLBACK-1', 'Product', 'test', 5, 0, 0, 10.00, '{}'::jsonb, TRUE)
                RETURNING id
                """, Long.class, tenant);

        assertThat(preflightIssueCount()).isZero();
        assertThat(tableExists("marketplace_webhook_inbox")).isTrue();
        assertThat(tableExists("marketplace_stock_sync_outbox")).isTrue();

        jdbc.execute("""
                CREATE FUNCTION block_sale_writes_for_rollback_rehearsal() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'sale writes paused during API rollback';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER block_sale_writes_for_rollback_rehearsal
                BEFORE INSERT OR UPDATE OR DELETE ON sales
                FOR EACH STATEMENT EXECUTE FUNCTION block_sale_writes_for_rollback_rehearsal()
                """);
        try {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO sales(system_client_id, product_id, quantity, total_value,
                                      channel, external_reference_id, status, resource)
                    VALUES (?, ?, 1, 10.00, 'MANUAL', 'OLD-API-WRITE', 'CONFIRMED', '{}'::jsonb)
                    """, tenant, product))
                    .hasMessageContaining("sale writes paused during API rollback");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE system_client_id=?",
                    Long.class, tenant)).isZero();
            assertThat(tableExists("marketplace_webhook_inbox")).isTrue();
            assertThat(tableExists("marketplace_stock_sync_outbox")).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS block_sale_writes_for_rollback_rehearsal ON sales");
            jdbc.execute("DROP FUNCTION IF EXISTS block_sale_writes_for_rollback_rehearsal()");
        }
    }

    private long preflightIssueCount() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM sales WHERE quantity <= 0 OR total_value < 0)
                  + (SELECT COUNT(*) FROM products
                       WHERE stock < 0 OR reserved_stock < 0 OR stock < reserved_stock)
                  + (SELECT COUNT(*) FROM sales
                       WHERE external_reference_id IS NOT NULL AND BTRIM(external_reference_id)='')
                """, Long.class);
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('public.' || ?) IS NOT NULL", Boolean.class, table));
    }
}
