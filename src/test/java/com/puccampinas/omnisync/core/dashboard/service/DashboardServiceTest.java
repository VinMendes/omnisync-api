package com.puccampinas.omnisync.core.dashboard.service;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardSalesDay;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardMetricsRepository;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardMetricsRepository.DashboardMetrics;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardMetricsRepository.ProductMetrics;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardMetricsRepository.RevenueMetrics;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);

    private final DashboardMetricsRepository repository = mock(DashboardMetricsRepository.class);
    private final DashboardService service = new DashboardService(repository, CLOCK);

    @Test
    void fillsEveryDayWithZerosAndAvoidsDivisionByZero() {
        when(repository.load(eq(3L), any(), any(), any(), any(), any())).thenReturn(new DashboardMetrics(
                new ProductMetrics(0, 0, BigDecimal.ZERO, 0, 0, 0, 0),
                new RevenueMetrics(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0),
                List.of(),
                List.of(),
                3
        ));

        var summary = service.getSummary(3L, "7d", principal(3L, "PERM_PRODUCT_READ", "PERM_SALE_READ"));

        assertThat(summary.totalProducts()).isZero();
        assertThat(summary.totalStock()).isZero();
        assertThat(summary.inventoryValue()).isEqualByComparingTo("0.00");
        assertThat(summary.lowStockCount()).isZero();
        assertThat(summary.activeListings()).isZero();
        assertThat(summary.revenueToday()).isEqualByComparingTo("0.00");
        assertThat(summary.salesTodayCount()).isZero();
        assertThat(summary.totalProductsChangePct()).isEqualByComparingTo("0.0");
        assertThat(summary.totalStockChangePct()).isEqualByComparingTo("0.0");
        assertThat(summary.activeListingsChangePct()).isEqualByComparingTo("0.0");
        assertThat(summary.revenueTodayChangePct()).isEqualByComparingTo("0.0");
        assertThat(summary.salesByDay()).hasSize(7);
        assertThat(summary.recentEvents()).isEmpty();
        assertThat(summary.salesByDay()).extracting(DashboardSalesDay::date)
                .containsExactly(
                        LocalDate.parse("2026-08-14"), LocalDate.parse("2026-08-15"),
                        LocalDate.parse("2026-08-16"), LocalDate.parse("2026-08-17"),
                        LocalDate.parse("2026-08-18"), LocalDate.parse("2026-08-19"),
                        LocalDate.parse("2026-08-20")
                );
        assertThat(summary.salesByDay()).allSatisfy(day -> {
            assertThat(day.total()).isEqualByComparingTo("0.00");
            assertThat(day.count()).isZero();
        });
    }

    @Test
    void calculatesChangesAndKeepsDatabaseDailyTotals() {
        when(repository.load(eq(3L), any(), any(), any(), any(), any())).thenReturn(new DashboardMetrics(
                new ProductMetrics(128, 4521, new BigDecimal("48250.90"), 7, 83, 125, 79),
                new RevenueMetrics(new BigDecimal("14290.00"), 18, new BigDecimal("12725.00"), 37),
                List.of(new DashboardSalesDay(LocalDate.parse("2026-08-20"), new BigDecimal("4200.00"), 12)),
                List.of(),
                4
        ));

        var summary = service.getSummary(3L, "7d", principal(3L, "PERM_PRODUCT_READ", "PERM_SALE_READ"));

        assertThat(summary.totalProductsChangePct()).isEqualByComparingTo("2.4");
        assertThat(summary.totalStockChangePct()).isEqualByComparingTo("-0.8");
        assertThat(summary.activeListingsChangePct()).isEqualByComparingTo("5.1");
        assertThat(summary.revenueTodayChangePct()).isEqualByComparingTo("12.3");
        assertThat(summary.inventoryValue()).isEqualByComparingTo("48250.90");
        assertThat(summary.lowStockCount()).isEqualTo(7);
        assertThat(summary.salesTodayCount()).isEqualTo(18);
        assertThat(summary.salesByDay().get(6).total()).isEqualByComparingTo("4200.00");
        assertThat(summary.salesByDay().get(6).count()).isEqualTo(12);
    }

    @Test
    void rejectsAnotherTenantBeforeRunningQueries() {
        assertThatThrownBy(() -> service.getSummary(3L, "7d", principal(4L, "PERM_PRODUCT_READ", "PERM_SALE_READ")))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository, never()).load(any(), any(), any(), any(), any(), any());
    }

    @Test
    void requiresBothReadPermissions() {
        assertThatThrownBy(() -> service.getSummary(3L, "7d", principal(3L, "PERM_SALE_READ")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getSummary(3L, "7d", principal(3L, "PERM_PRODUCT_READ")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsUnsupportedRange() {
        assertThatThrownBy(() -> service.getSummary(
                3L, "90d", principal(3L, "PERM_PRODUCT_READ", "PERM_SALE_READ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("range must be 7d or 30d.");
    }

    private OmniUserPrincipal principal(Long systemClientId, String... authorities) {
        return new OmniUserPrincipal(
                1L,
                systemClientId,
                "Dashboard user",
                "dashboard@example.com",
                null,
                true,
                true,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        );
    }
}
