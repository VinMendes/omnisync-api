package com.puccampinas.omnisync.core.dashboard.repository;

import com.puccampinas.omnisync.core.dashboard.dto.DashboardInventoryDay;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class DashboardAnalyticsRepository {

    private final JdbcClient jdbcClient;

    public DashboardAnalyticsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<DashboardInventoryDay> loadInventoryByDay(
            Long systemClientId,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd
    ) {
        return jdbcClient.sql("""
                        WITH tenant_bounds AS (
                            SELECT MIN(recorded_at) AS first_recorded_at
                            FROM inventory_history
                            WHERE system_client_id = :systemClientId
                        ), days AS (
                            SELECT day::date AS inventory_date
                            FROM tenant_bounds,
                                 LATERAL generate_series(
                                     GREATEST(CAST(:rangeStart AS date), first_recorded_at::date),
                                     CAST(:rangeEnd AS date) - 1,
                                     INTERVAL '1 day'
                                 ) day
                            WHERE first_recorded_at IS NOT NULL
                        ), latest_product_state AS (
                            SELECT
                                d.inventory_date,
                                h.product_id,
                                h.quantity,
                                h.unit_price,
                                h.active,
                                ROW_NUMBER() OVER (
                                    PARTITION BY d.inventory_date, h.product_id
                                    ORDER BY h.recorded_at DESC, h.id DESC
                                ) AS position
                            FROM days d
                            JOIN inventory_history h
                              ON h.system_client_id = :systemClientId
                             AND h.recorded_at < d.inventory_date + INTERVAL '1 day'
                        )
                        SELECT
                            d.inventory_date,
                            COALESCE(SUM(l.quantity) FILTER (
                                WHERE l.position = 1 AND l.active
                            ), 0) AS quantity,
                            COALESCE(SUM(l.quantity * l.unit_price) FILTER (
                                WHERE l.position = 1 AND l.active
                            ), 0) AS inventory_value
                        FROM days d
                        LEFT JOIN latest_product_state l ON l.inventory_date = d.inventory_date
                        GROUP BY d.inventory_date
                        ORDER BY d.inventory_date
                        """)
                .param("systemClientId", systemClientId)
                .param("rangeStart", rangeStart)
                .param("rangeEnd", rangeEnd)
                .query((rs, rowNum) -> new DashboardInventoryDay(
                        rs.getObject("inventory_date", LocalDate.class),
                        rs.getLong("quantity"),
                        rs.getBigDecimal("inventory_value").setScale(2, RoundingMode.HALF_UP)
                ))
                .list();
    }
}
