package com.puccampinas.omnisync.core.dashboard.dto;

import java.time.LocalDateTime;

public record DashboardRecentEvent(
        String id,
        String entityType,
        long entityId,
        String action,
        LocalDateTime createdAt
) {
}
