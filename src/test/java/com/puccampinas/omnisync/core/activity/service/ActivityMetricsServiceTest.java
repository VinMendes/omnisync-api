package com.puccampinas.omnisync.core.activity.service;

import com.puccampinas.omnisync.core.activity.repository.ActivityMetricsRepository;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ActivityMetricsServiceTest {

    private final ActivityMetricsRepository repository = mock(ActivityMetricsRepository.class);
    private final ActivityMetricsService service = new ActivityMetricsService(repository);

    @Test
    void validatesMarketplaceBeforeQuerying() {
        assertThatThrownBy(() -> service.getSummary(3L, "2026-09-20", "PHYSICAL", principal(3L, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("marketplace must be MERCADO_LIVRE, SHOPEE or AMAZON.");
        verify(repository, never()).loadSalesByHour(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingPermissionAndAnotherTenant() {
        assertThatThrownBy(() -> service.getSummary(3L, "2026-09-20", null, principal(3L, false)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getSummary(3L, "2026-09-20", null, principal(4L, true)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private OmniUserPrincipal principal(long tenant, boolean canReadSales) {
        return new OmniUserPrincipal(1L, tenant, "User", "user@example.com", null, true, true,
                canReadSales
                        ? List.of(new SimpleGrantedAuthority("PERM_SALE_READ"))
                        : List.of());
    }
}
