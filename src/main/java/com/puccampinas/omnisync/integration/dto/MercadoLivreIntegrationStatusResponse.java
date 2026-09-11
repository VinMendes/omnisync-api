package com.puccampinas.omnisync.integration.dto;

import java.time.LocalDateTime;
import java.time.Instant;

public record MercadoLivreIntegrationStatusResponse(
        boolean connected,
        Long systemClientId,
        Boolean active,
        LocalDateTime expiresAt,
        String marketplace,
        Instant lastSyncAt
) {
    public static MercadoLivreIntegrationStatusResponse notConnected(Long systemClientId) {
        return new MercadoLivreIntegrationStatusResponse(false, systemClientId, null, null, null, null);
    }
}
