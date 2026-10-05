package com.puccampinas.omnisync.core.dashboard.controller;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardAnalytics;
import com.puccampinas.omnisync.core.dashboard.service.DashboardAnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/{systemClientId}")
public class DashboardAnalyticsController {

    private final DashboardAnalyticsService service;

    public DashboardAnalyticsController(DashboardAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/analytics")
    public ResponseEntity<DashboardAnalytics> getAnalytics(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "7d") String range,
            @AuthenticationPrincipal OmniUserPrincipal principal
    ) {
        return ResponseEntity.ok(service.getAnalytics(systemClientId, range, principal));
    }
}
