package com.puccampinas.omnisync.core.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DashboardSponsoredSalesDay(LocalDate date, long count, BigDecimal total) {
}
