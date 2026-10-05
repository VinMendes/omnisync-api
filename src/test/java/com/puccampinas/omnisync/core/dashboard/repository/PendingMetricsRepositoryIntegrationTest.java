package com.puccampinas.omnisync.core.dashboard.repository;

import com.puccampinas.omnisync.core.activity.repository.ActivityMetricsRepository;
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
class PendingMetricsRepositoryIntegrationTest {

    @Autowired
    private DashboardAnalyticsRepository dashboardRepository;
    @Autowired
    private ActivityMetricsRepository activityRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void buildsInventoryHistoryOnlyFromPersistedSnapshotsAndIsolatesTenants() {
        long tenant = insertTenant("Inventory tenant", "71000000000100");
        long otherTenant = insertTenant("Other inventory tenant", "72000000000200");
        long first = insertProduct(tenant, "INV-1");
        long second = insertProduct(tenant, "INV-2");
        long other = insertProduct(otherTenant, "INV-OTHER");

        insertInventory(tenant, first, 10, "5.00", true, "2026-09-20T08:00:00");
        insertInventory(tenant, second, 3, "10.00", true, "2026-09-20T09:00:00");
        insertInventory(tenant, second, 3, "10.00", false, "2026-09-21T10:00:00");
        insertInventory(tenant, first, 8, "5.00", true, "2026-09-21T12:00:00");
        insertInventory(otherTenant, other, 9999, "999.00", true, "2026-09-20T08:00:00");

        var result = dashboardRepository.loadInventoryByDay(
                tenant,
                LocalDate.parse("2026-09-19").atStartOfDay(),
                LocalDate.parse("2026-09-23").atStartOfDay()
        );

        assertThat(result).hasSize(3);
        assertThat(result.get(0).date()).isEqualTo(LocalDate.parse("2026-09-20"));
        assertThat(result.get(0).quantity()).isEqualTo(13);
        assertThat(result.get(0).value()).isEqualByComparingTo("80.00");
        assertThat(result.get(1).quantity()).isEqualTo(8);
        assertThat(result.get(1).value()).isEqualByComparingTo("40.00");
        assertThat(result.get(2).quantity()).isEqualTo(8);
    }

    @Test
    void aggregatesConfirmedSalesByHourFillsZerosAndAppliesMarketplaceAndTenant() {
        long tenant = insertTenant("Activity tenant", "73000000000300");
        long otherTenant = insertTenant("Other activity tenant", "74000000000400");
        long product = insertProduct(tenant, "ACT-1");
        long otherProduct = insertProduct(otherTenant, "ACT-OTHER");

        insertSale(tenant, product, "MERCADO_LIVRE", "CONFIRMED", "100.00", "2026-09-20T08:10:00");
        insertSale(tenant, product, "MERCADO_LIVRE", "CONFIRMED", "320.00", "2026-09-20T08:50:00");
        insertSale(tenant, product, "MERCADO_LIVRE", "CONFIRMED", "780.00", "2026-09-20T09:10:00");
        insertSale(tenant, product, "MERCADO_LIVRE", "CANCELLED", "9000.00", "2026-09-20T09:30:00");
        insertSale(tenant, product, "PHYSICAL", "CONFIRMED", "50.00", "2026-09-20T10:00:00");
        insertSale(otherTenant, otherProduct, "MERCADO_LIVRE", "CONFIRMED", "9999.00", "2026-09-20T08:00:00");

        LocalDate date = LocalDate.parse("2026-09-20");
        var marketplace = activityRepository.loadSalesByHour(
                tenant, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), "MERCADO_LIVRE");

        assertThat(marketplace).hasSize(24);
        assertThat(marketplace.get(7).count()).isZero();
        assertThat(marketplace.get(7).total()).isEqualByComparingTo("0.00");
        assertThat(marketplace.get(8).count()).isEqualTo(2);
        assertThat(marketplace.get(8).total()).isEqualByComparingTo("420.00");
        assertThat(marketplace.get(9).count()).isEqualTo(1);
        assertThat(marketplace.get(9).total()).isEqualByComparingTo("780.00");
        assertThat(marketplace.get(10).count()).isZero();

        var allChannels = activityRepository.loadSalesByHour(
                tenant, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), null);
        assertThat(allChannels.get(10).count()).isEqualTo(1);
        assertThat(allChannels.get(10).total()).isEqualByComparingTo("50.00");
    }

    @Test
    void completesHourlyAggregationUnderOneSecondWithTenThousandSales() {
        long tenant = insertTenant("Activity performance", "75000000000500");
        long product = insertProduct(tenant, "ACT-PERF");
        jdbc.update("""
                INSERT INTO sales(system_client_id, product_id, quantity, total_value, channel, status, created_at)
                SELECT ?, ?, 1, 10.00, 'MERCADO_LIVRE', 'CONFIRMED', TIMESTAMP '2026-09-20 12:00:00'
                FROM generate_series(1, 10000)
                """, tenant, product);
        LocalDate date = LocalDate.parse("2026-09-20");

        activityRepository.loadSalesByHour(tenant, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), null);
        long startedAt = System.nanoTime();
        var result = activityRepository.loadSalesByHour(
                tenant, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), null);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.get(12).count()).isEqualTo(10000);
        assertThat(elapsedMillis).isLessThan(1000);
    }

    private long insertTenant(String name, String document) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class, name, document);
    }

    private long insertProduct(long tenant, String sku) {
        return jdbc.queryForObject("""
                INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                     minimum_stock, price, resource, active, created_at)
                VALUES (?, ?, ?, 'Test', 0, 0, 0, 1.00, '{}'::jsonb, true, NOW()) RETURNING id
                """, Long.class, tenant, sku, sku);
    }

    private void insertInventory(long tenant, long product, int quantity, String price,
                                 boolean active, String recordedAt) {
        jdbc.update("""
                INSERT INTO inventory_history(system_client_id, product_id, quantity, unit_price, active, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenant, product, quantity, new BigDecimal(price), active,
                Timestamp.valueOf(LocalDateTime.parse(recordedAt)));
    }

    private void insertSale(long tenant, long product, String channel, String status,
                            String total, String createdAt) {
        jdbc.update("""
                INSERT INTO sales(system_client_id, product_id, quantity, total_value, channel, status, created_at)
                VALUES (?, ?, 1, ?, ?, ?, ?)
                """, tenant, product, new BigDecimal(total), channel, status,
                Timestamp.valueOf(LocalDateTime.parse(createdAt)));
    }
}
