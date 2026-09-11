package com.puccampinas.omnisync.core.dashboard.repository;

import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@Transactional
class DashboardMetricsRepositoryIntegrationTest {

    private static final LocalDateTime RANGE_START = LocalDate.parse("2026-08-14").atStartOfDay();
    private static final LocalDateTime PREVIOUS_RANGE_START = LocalDate.parse("2026-08-07").atStartOfDay();
    private static final LocalDateTime RANGE_END = LocalDate.parse("2026-08-21").atStartOfDay();
    private static final LocalDateTime TODAY_START = LocalDate.parse("2026-08-20").atStartOfDay();
    private static final LocalDateTime YESTERDAY_START = LocalDate.parse("2026-08-19").atStartOfDay();

    @Autowired
    private DashboardMetricsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aggregatesOnlyTheRequestedTenantAndUsesConfirmationLogDate() {
        long tenant = insertTenant("Dashboard tenant", "10000000000100");
        long anotherTenant = insertTenant("Other tenant", "20000000000200");
        long product = insertProduct(tenant, "SKU-1", 15, true, "MLB-1", LocalDateTime.parse("2026-08-01T10:00:00"));
        insertProduct(tenant, "SKU-2", 5, true, null, LocalDateTime.parse("2026-08-18T10:00:00"));
        long otherProduct = insertProduct(anotherTenant, "OTHER-1", 9999, true, "MLB-OTHER", LocalDateTime.parse("2026-08-01T10:00:00"));

        long todaySale = insertSale(tenant, product, 2, "100.00", "CONFIRMED", LocalDateTime.parse("2026-08-20T09:00:00"));
        insertSaleLog(todaySale, tenant, "CREATED", "CONFIRMED", LocalDateTime.parse("2026-08-20T09:00:01"));

        long confirmedLater = insertSale(tenant, product, 1, "50.00", "CONFIRMED", LocalDateTime.parse("2026-08-10T09:00:00"));
        insertSaleLog(confirmedLater, tenant, "CREATED", "PENDING", LocalDateTime.parse("2026-08-10T09:00:01"));
        insertSaleLog(confirmedLater, tenant, "UPDATED", "CONFIRMED", LocalDateTime.parse("2026-08-19T14:00:00"));

        insertSale(tenant, product, 8, "800.00", "CANCELLED", LocalDateTime.parse("2026-08-20T11:00:00"));
        insertSale(anotherTenant, otherProduct, 50, "50000.00", "CONFIRMED", LocalDateTime.parse("2026-08-20T12:00:00"));

        var metrics = load(tenant);

        assertThat(metrics.products().totalProducts()).isEqualTo(2);
        assertThat(metrics.products().totalStock()).isEqualTo(20);
        assertThat(metrics.products().inventoryValue()).isEqualByComparingTo("200.00");
        assertThat(metrics.products().lowStockCount()).isZero();
        assertThat(metrics.products().activeListings()).isEqualTo(1);
        assertThat(metrics.products().previousProducts()).isEqualTo(1);
        assertThat(metrics.revenue().revenueToday()).isEqualByComparingTo("100.00");
        assertThat(metrics.revenue().salesTodayCount()).isEqualTo(1);
        assertThat(metrics.revenue().revenueYesterday()).isEqualByComparingTo("50.00");
        assertThat(metrics.revenue().soldQuantityInRange()).isEqualTo(3);
        assertThat(metrics.salesByDay()).hasSize(2);
        assertThat(metrics.salesByDay().get(0).date()).isEqualTo(LocalDate.parse("2026-08-19"));
        assertThat(metrics.salesByDay().get(0).total()).isEqualByComparingTo("50.00");
        assertThat(metrics.salesByDay().get(1).date()).isEqualTo(LocalDate.parse("2026-08-20"));
        assertThat(metrics.salesByDay().get(1).total()).isEqualByComparingTo("100.00");
        assertThat(metrics.recentEvents()).hasSize(3);
        assertThat(metrics.recentEvents()).allSatisfy(event -> {
            assertThat(event.entityType()).isEqualTo("SALE");
            assertThat(event.id()).startsWith("SALE:");
        });
    }

    @Test
    void returnsZeroAggregatesWhenTenantHasNoProductsOrSales() {
        long tenant = insertTenant("Empty tenant", "40000000000400");

        var metrics = load(tenant);

        assertThat(metrics.products().totalProducts()).isZero();
        assertThat(metrics.products().totalStock()).isZero();
        assertThat(metrics.products().inventoryValue()).isEqualByComparingTo("0");
        assertThat(metrics.products().lowStockCount()).isZero();
        assertThat(metrics.products().activeListings()).isZero();
        assertThat(metrics.revenue().revenueToday()).isEqualByComparingTo("0");
        assertThat(metrics.revenue().salesTodayCount()).isZero();
        assertThat(metrics.revenue().revenueYesterday()).isEqualByComparingTo("0");
        assertThat(metrics.revenue().soldQuantityInRange()).isZero();
        assertThat(metrics.salesByDay()).isEmpty();
        assertThat(metrics.recentEvents()).isEmpty();
    }

    @Test
    void completesDashboardQueriesUnder500MillisWithTenThousandSales() {
        long tenant = insertTenant("Performance tenant", "30000000000300");
        long product = insertProduct(tenant, "PERF-1", 20000, true, "MLB-PERF", LocalDateTime.parse("2026-08-01T10:00:00"));
        jdbc.update("""
                INSERT INTO sales(system_client_id, product_id, quantity, total_value, channel, status, created_at)
                SELECT ?, ?, 1, 10.00, 'MANUAL', 'CONFIRMED', TIMESTAMP '2026-08-20 10:00:00'
                FROM generate_series(1, 10000)
                """, tenant, product);

        load(tenant); // aquece plano, conexao e classes antes da medicao usada como evidencia
        var measured = load(tenant);

        assertThat(measured.salesByDay()).singleElement().satisfies(day -> {
            assertThat(day.total()).isEqualByComparingTo(new BigDecimal("100000.00"));
            assertThat(day.count()).isEqualTo(10000);
        });
        assertThat(measured.queryElapsedMillis()).isLessThan(500);
    }

    @Test
    void calculatesInventoryValueAndCountsProductsBelowOrExactlyAtMinimumStock() {
        long tenant = insertTenant("Stock policy tenant", "50000000000500");
        long anotherTenant = insertTenant("Isolated stock tenant", "60000000000600");

        insertProductWithStockPolicy(tenant, "BELOW", 4, 1, 5, "20.00", true);
        insertProductWithStockPolicy(tenant, "EQUAL", 7, 2, 5, "10.00", true);
        insertProductWithStockPolicy(tenant, "ABOVE", 8, 2, 5, "5.00", true);
        insertProductWithStockPolicy(tenant, "INACTIVE", 1, 0, 5, "1000.00", false);
        insertProductWithStockPolicy(anotherTenant, "OTHER", 1, 0, 5, "1000.00", true);

        var metrics = load(tenant);

        assertThat(metrics.products().lowStockCount()).isEqualTo(2);
        assertThat(metrics.products().inventoryValue()).isEqualByComparingTo("190.00");
    }

    private DashboardMetricsRepository.DashboardMetrics load(long tenant) {
        return repository.load(
                tenant, RANGE_START, PREVIOUS_RANGE_START, RANGE_END, TODAY_START, YESTERDAY_START
        );
    }

    private long insertTenant(String name, String document) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class, name, document
        );
    }

    private long insertProduct(long tenant, String sku, int stock, boolean active, String mlItemId, LocalDateTime createdAt) {
        String resource = mlItemId == null ? "{}" : "{\"mercado_livre\":{\"item_id\":\"" + mlItemId + "\"}}";
        return jdbc.queryForObject("""
                        INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                             minimum_stock, price, resource, active, created_at)
                        VALUES (?, ?, ?, 'Test product', ?, 0, 0, 10.00, ?::jsonb, ?, ?) RETURNING id
                        """,
                Long.class, tenant, sku, sku, stock, resource, active, Timestamp.valueOf(createdAt)
        );
    }

    private long insertProductWithStockPolicy(
            long tenant,
            String sku,
            int stock,
            int reservedStock,
            int minimumStock,
            String price,
            boolean active
    ) {
        return jdbc.queryForObject("""
                        INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                             minimum_stock, price, resource, active, created_at)
                        VALUES (?, ?, ?, 'Test product', ?, ?, ?, ?, '{}'::jsonb, ?, ?) RETURNING id
                        """,
                Long.class,
                tenant,
                sku,
                sku,
                stock,
                reservedStock,
                minimumStock,
                new BigDecimal(price),
                active,
                Timestamp.valueOf(LocalDateTime.parse("2026-08-01T10:00:00"))
        );
    }

    private long insertSale(
            long tenant, long product, int quantity, String total, String status, LocalDateTime createdAt
    ) {
        return jdbc.queryForObject("""
                        INSERT INTO sales(system_client_id, product_id, quantity, total_value, channel, status, created_at)
                        VALUES (?, ?, ?, ?, 'MANUAL', ?, ?) RETURNING id
                        """,
                Long.class, tenant, product, quantity, new BigDecimal(total), status, Timestamp.valueOf(createdAt)
        );
    }

    private void insertSaleLog(long sale, long tenant, String action, String newStatus, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO sales_logs(sale_id, system_client_id, action, new_status, metadata, created_at)
                        VALUES (?, ?, ?, ?, '{}'::jsonb, ?)
                        """,
                sale, tenant, action, newStatus, Timestamp.valueOf(createdAt)
        );
    }
}
