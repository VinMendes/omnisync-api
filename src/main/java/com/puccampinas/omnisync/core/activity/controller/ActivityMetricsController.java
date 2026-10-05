package com.puccampinas.omnisync.core.activity.controller;

import com.puccampinas.omnisync.core.activity.dto.ActivitySummary;
import com.puccampinas.omnisync.core.activity.service.ActivityMetricsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activity/{systemClientId}")
public class ActivityMetricsController {

    private final ActivityMetricsService service;

    public ActivityMetricsController(ActivityMetricsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<ActivitySummary> getSummary(
            @PathVariable Long systemClientId,
            @RequestParam String date,
            @RequestParam(required = false) String marketplace,
            @AuthenticationPrincipal OmniUserPrincipal principal
    ) {
        return ResponseEntity.ok(service.getSummary(systemClientId, date, marketplace, principal));
    }
}
