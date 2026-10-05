package com.puccampinas.omnisync.core.dashboard.dto;

import java.util.List;

public record DashboardAnalytics(
        List<DashboardInventoryDay> inventoryByDay,
        List<DashboardSponsoredSalesDay> sponsoredSalesByDay
) {
}
