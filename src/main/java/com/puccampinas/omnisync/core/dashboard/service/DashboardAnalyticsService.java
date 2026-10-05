package com.puccampinas.omnisync.core.dashboard.service;

import com.puccampinas.omnisync.config.security.PermissionAuthority;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardAnalytics;
import com.puccampinas.omnisync.core.dashboard.repository.DashboardAnalyticsRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardAnalyticsService {

    private final DashboardAnalyticsRepository repository;
    private final Clock clock;

    @Autowired
    public DashboardAnalyticsService(DashboardAnalyticsRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    DashboardAnalyticsService(DashboardAnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardAnalytics getAnalytics(
            Long systemClientId,
            String requestedRange,
            OmniUserPrincipal principal
    ) {
        validateAccess(systemClientId, principal);
        int days = parseRange(requestedRange);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime rangeStart = today.minusDays(days - 1L).atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();

        return new DashboardAnalytics(
                repository.loadInventoryByDay(systemClientId, rangeStart, rangeEnd),
                List.of()
        );
    }

    private void validateAccess(Long systemClientId, OmniUserPrincipal principal) {
        if (systemClientId == null || systemClientId <= 0) {
            throw new IllegalArgumentException("System client id must be greater than zero.");
        }
        if (principal == null || !systemClientId.equals(principal.getSystemClientId())) {
            throw new EntityNotFoundException("System client not found for the authenticated user.");
        }
        List<String> authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        if (!authorities.contains(PermissionAuthority.PRODUCT_READ)
                || !authorities.contains(PermissionAuthority.SALE_READ)) {
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
}
