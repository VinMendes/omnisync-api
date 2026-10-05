package com.puccampinas.omnisync.core.activity.repository;

import com.puccampinas.omnisync.core.activity.dto.ActivitySalesHour;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ActivityMetricsRepository {

    private final JdbcClient jdbcClient;

    public ActivityMetricsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ActivitySalesHour> loadSalesByHour(
            Long systemClientId,
            LocalDateTime dayStart,
            LocalDateTime dayEnd,
            String marketplace
    ) {
        return jdbcClient.sql("""
                        WITH hours AS (
                            SELECT generate_series(0, 23) AS hour
                        ), confirmed_sales AS (
                            SELECT
                                s.id,
                                s.total_value,
                                EXTRACT(HOUR FROM COALESCE(
                                    MIN(sl.created_at) FILTER (WHERE sl.new_status = 'CONFIRMED'),
                                    s.created_at
                                ))::integer AS sale_hour
                            FROM sales s
                            LEFT JOIN sales_logs sl
                              ON sl.sale_id = s.id
                             AND sl.system_client_id = :systemClientId
                            WHERE s.system_client_id = :systemClientId
                              AND s.status = 'CONFIRMED'
                              AND (:marketplace = '' OR s.channel = :marketplace)
                            GROUP BY s.id, s.total_value, s.created_at
                            HAVING COALESCE(
                                MIN(sl.created_at) FILTER (WHERE sl.new_status = 'CONFIRMED'),
                                s.created_at
                            ) >= :dayStart
                               AND COALESCE(
                                MIN(sl.created_at) FILTER (WHERE sl.new_status = 'CONFIRMED'),
                                s.created_at
                            ) < :dayEnd
                        )
                        SELECT
                            h.hour,
                            COUNT(c.id) AS sale_count,
                            COALESCE(SUM(c.total_value), 0) AS total
                        FROM hours h
                        LEFT JOIN confirmed_sales c ON c.sale_hour = h.hour
                        GROUP BY h.hour
                        ORDER BY h.hour
                        """)
                .param("systemClientId", systemClientId)
                .param("marketplace", marketplace == null ? "" : marketplace)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd)
                .query((rs, rowNum) -> new ActivitySalesHour(
                        rs.getInt("hour"),
                        rs.getLong("sale_count"),
                        rs.getBigDecimal("total").setScale(2, RoundingMode.HALF_UP)
                ))
                .list();
    }
}
