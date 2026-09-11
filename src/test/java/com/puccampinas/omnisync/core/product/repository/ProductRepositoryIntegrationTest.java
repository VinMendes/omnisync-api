package com.puccampinas.omnisync.core.product.repository;

import com.puccampinas.omnisync.common.util.OffsetLimitPageable;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@Transactional
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findsOnlyActiveLowStockProductsFromRequestedTenantIncludingExactLimit() {
        long tenant = insertTenant("Low stock tenant", "70000000000700");
        long anotherTenant = insertTenant("Other low stock tenant", "80000000000800");

        insertProduct(tenant, "BELOW", 4, 1, 5, true);
        insertProduct(tenant, "EQUAL", 7, 2, 5, true);
        insertProduct(tenant, "ABOVE", 8, 2, 5, true);
        insertProduct(tenant, "INACTIVE", 1, 0, 5, false);
        insertProduct(anotherTenant, "OTHER", 1, 0, 5, true);

        var result = repository.findLowStockProducts(tenant, new OffsetLimitPageable(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(product -> product.getSku())
                .containsExactly("BELOW", "EQUAL");
    }

    @Test
    void paginatesLowStockProductsUsingOffsetAndLimit() {
        long tenant = insertTenant("Paginated stock tenant", "90000000000900");
        insertProduct(tenant, "LOW-1", 1, 0, 5, true);
        insertProduct(tenant, "LOW-2", 2, 0, 5, true);

        var first = repository.findLowStockProducts(tenant, new OffsetLimitPageable(0, 1));
        var second = repository.findLowStockProducts(tenant, new OffsetLimitPageable(1, 1));

        assertThat(first.getContent()).extracting(product -> product.getSku()).containsExactly("LOW-1");
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(product -> product.getSku()).containsExactly("LOW-2");
        assertThat(second.hasNext()).isFalse();
    }

    private long insertTenant(String name, String document) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                name,
                document
        );
    }

    private void insertProduct(
            long tenant,
            String sku,
            int stock,
            int reservedStock,
            int minimumStock,
            boolean active
    ) {
        jdbc.update("""
                        INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                             minimum_stock, price, resource, active)
                        VALUES (?, ?, ?, 'Test product', ?, ?, ?, ?, '{}'::jsonb, ?)
                        """,
                tenant,
                sku,
                sku,
                stock,
                reservedStock,
                minimumStock,
                new BigDecimal("10.00"),
                active
        );
    }
}
