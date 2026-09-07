package com.puccampinas.omnisync.core.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardSummary(
        long totalProducts,
        BigDecimal totalProductsChangePct,
        long totalStock,
        BigDecimal totalStockChangePct,
        long activeListings,
        BigDecimal activeListingsChangePct,
        BigDecimal revenueToday,
        BigDecimal revenueTodayChangePct,
        List<DashboardSalesDay> salesByDay
) {
}
