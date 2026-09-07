package com.puccampinas.omnisync.core.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DashboardSalesDay(LocalDate date, BigDecimal total, long count) {
}
