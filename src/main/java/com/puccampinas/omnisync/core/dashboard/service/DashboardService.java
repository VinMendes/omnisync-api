package com.puccampinas.omnisync.core.dashboard.service;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardSalesDay;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardSummary;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardMetricsRepository;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardMetricsRepository.DashboardMetrics;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    private final DashboardMetricsRepository repository;
    private final Clock clock;

    @Autowired
    public DashboardService(DashboardMetricsRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    DashboardService(DashboardMetricsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardSummary getSummary(Long systemClientId, String requestedRange, OmniUserPrincipal principal) {
        validateAccess(systemClientId, principal);
        int days = parseRange(requestedRange);

        LocalDate today = LocalDate.now(clock);
        LocalDate rangeStartDate = today.minusDays(days - 1L);
        LocalDateTime rangeStart = rangeStartDate.atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime previousRangeStart = rangeStart.minusDays(days);

        DashboardMetrics metrics = repository.load(
                systemClientId,
                rangeStart,
                previousRangeStart,
                rangeEnd,
                today.atStartOfDay(),
                today.minusDays(1).atStartOfDay()
        );

        long previousStock = metrics.products().totalStock() + metrics.revenue().soldQuantityInRange();
        Map<LocalDate, DashboardSalesDay> totalsByDate = new LinkedHashMap<>();
        metrics.salesByDay().forEach(day -> totalsByDate.put(day.date(), day));
        List<DashboardSalesDay> completeSeries = rangeStartDate.datesUntil(today.plusDays(1))
                .map(date -> totalsByDate.getOrDefault(date, new DashboardSalesDay(date, ZERO_MONEY, 0)))
                .toList();

        return new DashboardSummary(
                metrics.products().totalProducts(),
                percentageChange(metrics.products().totalProducts(), metrics.products().previousProducts()),
                metrics.products().totalStock(),
                percentageChange(metrics.products().totalStock(), previousStock),
                metrics.products().activeListings(),
                percentageChange(metrics.products().activeListings(), metrics.products().previousListings()),
                money(metrics.revenue().revenueToday()),
                percentageChange(metrics.revenue().revenueToday(), metrics.revenue().revenueYesterday()),
                completeSeries
        );
    }

    private void validateAccess(Long systemClientId, OmniUserPrincipal principal) {
        if (systemClientId == null || systemClientId <= 0) {
            throw new IllegalArgumentException("System client id must be greater than zero.");
        }
        if (principal == null || !systemClientId.equals(principal.getSystemClientId())) {
            throw new EntityNotFoundException("System client not found for the authenticated user.");
        }

        List<String> authorities = principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        if (!authorities.contains("PRODUCT_READ") || !authorities.contains("SALE_READ")) {
            throw new AccessDeniedException("PRODUCT_READ and SALE_READ permissions are required.");
        }
    }

    private int parseRange(String requestedRange) {
        return switch (requestedRange == null ? "7d" : requestedRange.trim().toLowerCase()) {
            case "7d" -> 7;
            case "30d" -> 30;
            default -> throw new IllegalArgumentException("range must be 7d or 30d.");
        };
    }

    private BigDecimal percentageChange(long current, long previous) {
        return percentageChange(BigDecimal.valueOf(current), BigDecimal.valueOf(previous));
    }

    private BigDecimal percentageChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO_MONEY : value.setScale(2, RoundingMode.HALF_UP);
    }
}
