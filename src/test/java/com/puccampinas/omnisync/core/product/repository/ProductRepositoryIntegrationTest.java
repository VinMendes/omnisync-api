package com.puccampinas.omnisync.core.product.repository;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductRepositoryIntegrationTest {

    @Test
    void mercadoLivreIdentityIsUniquePerTenantButReusableAcrossTenants() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long first = tenant(jdbc, "repo-a");
            long second = tenant(jdbc, "repo-b");
            insert(jdbc, first, "A", "MLB-SHARED");
            insert(jdbc, second, "B", "MLB-SHARED");
            assertThatThrownBy(() -> insert(jdbc, first, "C", "MLB-SHARED"))
                    .hasMessageContaining("uk_products_client_ml_item_id");
        }
    }

    private long tenant(JdbcTemplate jdbc, String document) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES ('Repository tenant', ?) RETURNING id",
                Long.class,
                document
        );
    }

    private void insert(JdbcTemplate jdbc, long tenant, String sku, String itemId) {
        jdbc.update("""
                INSERT INTO products(system_client_id, sku, name, description, stock,
                                     reserved_stock, price, resource, active)
                VALUES (?, ?, 'Product', 'Description', 0, 0, 1.00,
                        jsonb_build_object('mercado_livre', jsonb_build_object('item_id', ?)), TRUE)
                """, tenant, sku, itemId);
    }
}
