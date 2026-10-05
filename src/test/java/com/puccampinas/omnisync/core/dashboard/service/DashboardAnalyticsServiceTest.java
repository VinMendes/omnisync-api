package com.puccampinas.omnisync.core.dashboard.service;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardAnalyticsRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardAnalyticsServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
    private final DashboardAnalyticsRepository repository = mock(DashboardAnalyticsRepository.class);
    private final DashboardAnalyticsService service = new DashboardAnalyticsService(repository, CLOCK);

    @Test
    void appliesRangeAndReturnsEmptySponsoredSeriesWithoutOfficialSource() {
        when(repository.loadInventoryByDay(eq(3L), any(), any())).thenReturn(java.util.List.of());

        var result = service.getAnalytics(3L, "7d", principal(3L, "PERM_PRODUCT_READ", "PERM_SALE_READ"));

        assertThat(result.inventoryByDay()).isEmpty();
        assertThat(result.sponsoredSalesByDay()).isEmpty();
        verify(repository).loadInventoryByDay(
                3L,
                java.time.LocalDateTime.parse("2026-09-14T00:00:00"),
                java.time.LocalDateTime.parse("2026-09-21T00:00:00"));
    }

    @Test
    void rejectsInsufficientPermissionAndAnotherTenant() {
        assertThatThrownBy(() -> service.getAnalytics(3L, "7d", principal(3L, "PERM_PRODUCT_READ")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getAnalytics(
                3L, "7d", principal(4L, "PERM_PRODUCT_READ", "PERM_SALE_READ")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private OmniUserPrincipal principal(long tenant, String... authorities) {
        return new OmniUserPrincipal(1L, tenant, "User", "user@example.com", null, true, true,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
}
