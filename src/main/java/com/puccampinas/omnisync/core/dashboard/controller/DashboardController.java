package com.puccampinas.omnisync.core.dashboard.controller;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardSummary;
import com.puccampinas.omnisync.core.dashboard.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/{systemClientId}")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummary> getSummary(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "7d") String range,
            @AuthenticationPrincipal OmniUserPrincipal principal
    ) {
        return ResponseEntity.ok(dashboardService.getSummary(systemClientId, range, principal));
    }
}
