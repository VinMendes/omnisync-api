package com.puccampinas.omnisync.core.activity.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.config.security.PermissionAuthority;
import com.puccampinas.omnisync.core.activity.dto.ActivitySummary;
import com.puccampinas.omnisync.core.activity.repository.ActivityMetricsRepository;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
public class ActivityMetricsService {

    private final ActivityMetricsRepository repository;

    public ActivityMetricsService(ActivityMetricsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ActivitySummary getSummary(
            Long systemClientId,
            String requestedDate,
            String requestedMarketplace,
            OmniUserPrincipal principal
    ) {
        validateAccess(systemClientId, principal);
        LocalDate date = parseDate(requestedDate);
        String marketplace = parseMarketplace(requestedMarketplace);
        return new ActivitySummary(repository.loadSalesByHour(
                systemClientId,
                date.atStartOfDay(),
                date.plusDays(1).atStartOfDay(),
                marketplace
        ));
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
        if (!authorities.contains(PermissionAuthority.SALE_READ)) {
            throw new AccessDeniedException("SALE_READ permission is required.");
        }
    }

    private LocalDate parseDate(String requestedDate) {
        try {
            return LocalDate.parse(requestedDate);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException("date must use ISO-8601 format (yyyy-MM-dd).", exception);
        }
    }

    private String parseMarketplace(String requestedMarketplace) {
        if (requestedMarketplace == null || requestedMarketplace.isBlank()) {
            return null;
        }
        try {
            return Marketplace.valueOf(requestedMarketplace.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("marketplace must be MERCADO_LIVRE, SHOPEE or AMAZON.", exception);
        }
    }
}
