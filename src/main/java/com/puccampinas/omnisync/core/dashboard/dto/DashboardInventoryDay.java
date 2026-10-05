package com.puccampinas.omnisync.core.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DashboardInventoryDay(LocalDate date, long quantity, BigDecimal value) {
}
