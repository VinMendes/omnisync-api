package com.puccampinas.omnisync.core.dashboard.repository;

import com.puccampinas.omnisync.core.dashboard.dto.DashboardSalesDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class DashboardMetricsRepository {

    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsRepository.class);

    private final JdbcClient jdbcClient;

    public DashboardMetricsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DashboardMetrics load(
            Long systemClientId,
            LocalDateTime rangeStart,
            LocalDateTime previousRangeStart,
            LocalDateTime rangeEnd,
            LocalDateTime todayStart,
            LocalDateTime yesterdayStart
    ) {
        long startedAt = System.nanoTime();

        ProductMetrics products = jdbcClient.sql("""
                        SELECT
                            COUNT(*) FILTER (WHERE active) AS total_products,
                            COALESCE(SUM(stock) FILTER (WHERE active), 0) AS total_stock,
                            COUNT(*) FILTER (
                                WHERE active
                                  AND NULLIF(BTRIM(resource -> 'mercado_livre' ->> 'item_id'), '') IS NOT NULL
                            ) AS active_listings,
                            COUNT(*) FILTER (WHERE active AND created_at < :rangeStart) AS previous_products,
                            COUNT(*) FILTER (
                                WHERE active
                                  AND created_at < :rangeStart
                                  AND NULLIF(BTRIM(resource -> 'mercado_livre' ->> 'item_id'), '') IS NOT NULL
                            ) AS previous_listings
                        FROM products
                        WHERE system_client_id = :systemClientId
                        """)
                .param("systemClientId", systemClientId)
                .param("rangeStart", rangeStart)
                .query((rs, rowNum) -> new ProductMetrics(
                        rs.getLong("total_products"),
                        rs.getLong("total_stock"),
                        rs.getLong("active_listings"),
                        rs.getLong("previous_products"),
                        rs.getLong("previous_listings")
                ))
                .single();

        RevenueMetrics revenue = jdbcClient.sql("""
                        WITH confirmed_sales AS (
                            SELECT
                                s.id,
                                s.total_value,
                                s.quantity,
                                p.active AS active_product,
                                COALESCE(BOOL_OR(
                                    (sl.metadata ->> 'stock_delta') ~ '^-?[0-9]+$'
                                ), FALSE) AS has_logged_stock_delta,
                                COALESCE(
                                    MIN(sl.created_at) FILTER (WHERE sl.new_status = 'CONFIRMED'),
                                    s.created_at
                                ) AS confirmed_at
                            FROM sales s
                            JOIN products p
                              ON p.id = s.product_id
                             AND p.system_client_id = :systemClientId
                            LEFT JOIN sales_logs sl
                              ON sl.sale_id = s.id
                             AND sl.system_client_id = :systemClientId
                            WHERE s.system_client_id = :systemClientId
                              AND s.status = 'CONFIRMED'
                            GROUP BY s.id, s.total_value, s.quantity, s.created_at, p.active
                        ), logged_stock_delta AS (
                            SELECT COALESCE(SUM((sl.metadata ->> 'stock_delta')::bigint), 0) AS quantity
                            FROM sales_logs sl
                            JOIN sales s
                              ON s.id = sl.sale_id
                             AND s.system_client_id = :systemClientId
                            JOIN products p
                              ON p.id = s.product_id
                             AND p.system_client_id = :systemClientId
                             AND p.active
                            WHERE sl.system_client_id = :systemClientId
                              AND sl.created_at >= :rangeStart
                              AND sl.created_at < :rangeEnd
                              AND (sl.metadata ->> 'stock_delta') ~ '^-?[0-9]+$'
                        )
                        SELECT
                            COALESCE(SUM(total_value) FILTER (
                                WHERE confirmed_at >= :todayStart AND confirmed_at < :rangeEnd
                            ), 0) AS revenue_today,
                            COALESCE(SUM(total_value) FILTER (
                                WHERE confirmed_at >= :yesterdayStart AND confirmed_at < :todayStart
                            ), 0) AS revenue_yesterday,
                            (SELECT quantity FROM logged_stock_delta) + COALESCE(SUM(quantity) FILTER (
                                WHERE confirmed_at >= :rangeStart
                                  AND confirmed_at < :rangeEnd
                                  AND active_product
                                  AND NOT has_logged_stock_delta
                            ), 0) AS sold_quantity_in_range
                        FROM confirmed_sales
                        WHERE confirmed_at >= :previousRangeStart
                          AND confirmed_at < :rangeEnd
                        """)
                .param("systemClientId", systemClientId)
                .param("previousRangeStart", previousRangeStart)
                .param("rangeStart", rangeStart)
                .param("rangeEnd", rangeEnd)
                .param("todayStart", todayStart)
                .param("yesterdayStart", yesterdayStart)
                .query((rs, rowNum) -> new RevenueMetrics(
                        rs.getBigDecimal("revenue_today"),
                        rs.getBigDecimal("revenue_yesterday"),
                        rs.getLong("sold_quantity_in_range")
                ))
                .single();

        List<DashboardSalesDay> salesByDay = jdbcClient.sql("""
                        WITH confirmed_sales AS (
                            SELECT
                                s.id,
                                s.total_value,
                                COALESCE(
                                    MIN(sl.created_at) FILTER (WHERE sl.new_status = 'CONFIRMED'),
                                    s.created_at
                                ) AS confirmed_at
                            FROM sales s
                            LEFT JOIN sales_logs sl
                              ON sl.sale_id = s.id
                             AND sl.system_client_id = :systemClientId
                            WHERE s.system_client_id = :systemClientId
                              AND s.status = 'CONFIRMED'
                            GROUP BY s.id, s.total_value, s.created_at
                        )
                        SELECT
                            confirmed_at::date AS sale_date,
                            COALESCE(SUM(total_value), 0) AS total,
                            COUNT(*) AS sale_count
                        FROM confirmed_sales
                        WHERE confirmed_at >= :rangeStart
                          AND confirmed_at < :rangeEnd
                        GROUP BY confirmed_at::date
                        ORDER BY sale_date
                        """)
                .param("systemClientId", systemClientId)
                .param("rangeStart", rangeStart)
                .param("rangeEnd", rangeEnd)
                .query((rs, rowNum) -> new DashboardSalesDay(
                        rs.getObject("sale_date", LocalDate.class),
                        rs.getBigDecimal("total"),
                        rs.getLong("sale_count")
                ))
                .list();

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Dashboard summary queries completed in {} ms for systemClientId={} rangeStart={} rangeEnd={}",
                elapsedMillis, systemClientId, rangeStart, rangeEnd);

        return new DashboardMetrics(products, revenue, salesByDay, elapsedMillis);
    }

    public record ProductMetrics(
            long totalProducts,
            long totalStock,
            long activeListings,
            long previousProducts,
            long previousListings
    ) {
    }

    public record RevenueMetrics(
            BigDecimal revenueToday,
            BigDecimal revenueYesterday,
            long soldQuantityInRange
    ) {
    }

    public record DashboardMetrics(
            ProductMetrics products,
            RevenueMetrics revenue,
            List<DashboardSalesDay> salesByDay,
            long queryElapsedMillis
    ) {
    }
}
