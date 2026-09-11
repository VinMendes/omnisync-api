package com.puccampinas.omnisync.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientMercadoLivreSyncMigrationIntegrationTest {

    @Test
    void addsNullableAbsoluteTimestampAndEnforcesIdentityWithinTenantOnly() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long firstTenant = tenant(jdbc, "first");
            long secondTenant = tenant(jdbc, "second");
            jdbc.update("""
                    INSERT INTO marketplace_integrations(
                        system_client_id, marketplace, access_token, expires_at
                    ) VALUES (?, 'MERCADO_LIVRE', 'encrypted', NOW())
                    """, firstTenant);

            assertThat(jdbc.queryForObject(
                    "SELECT last_sync_at FROM marketplace_integrations WHERE system_client_id = ?",
                    Timestamp.class,
                    firstTenant
            )).isNull();

            product(jdbc, firstTenant, "SKU-1", "MLB-1");
            product(jdbc, secondTenant, "SKU-1", "MLB-1");
            assertThatThrownBy(() -> product(jdbc, firstTenant, "SKU-2", "MLB-1"))
                    .hasMessageContaining("uk_products_client_ml_item_id");

            Instant expected = Instant.parse("2026-09-08T15:00:00Z");
            jdbc.update("""
                    UPDATE marketplace_integrations
                       SET last_sync_at = ?
                     WHERE system_client_id = ?
                    """, Timestamp.from(expected), firstTenant);
            assertThat(jdbc.queryForObject(
                    "SELECT last_sync_at FROM marketplace_integrations WHERE system_client_id = ?",
                    Timestamp.class,
                    firstTenant
            ).toInstant()).isEqualTo(expected);
        }
    }

    @Test
    void abortsBeforeCreatingTheIndexWhenHistoricalDuplicatesExist() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).target("11").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long tenant = tenant(jdbc, "duplicate");
            product(jdbc, tenant, "SKU-A", "MLB-DUP");
            product(jdbc, tenant, "SKU-B", "MLB-DUP");

            assertThatThrownBy(() -> Flyway.configure().dataSource(dataSource).load().migrate())
                    .hasStackTraceContaining("V12 aborted")
                    .hasStackTraceContaining("1 tenant/item identity group");
        }
    }

    private long tenant(JdbcTemplate jdbc, String suffix) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Tenant " + suffix,
                "doc-" + suffix
        );
    }

    private void product(JdbcTemplate jdbc, long tenant, String sku, String itemId) {
        jdbc.update("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    price, resource, active
                ) VALUES (?, ?, 'Product', 'Description', 1, 0, 10.00,
                          jsonb_build_object('mercado_livre', jsonb_build_object('item_id', ?)), TRUE)
                """, tenant, sku, itemId);
    }
}
