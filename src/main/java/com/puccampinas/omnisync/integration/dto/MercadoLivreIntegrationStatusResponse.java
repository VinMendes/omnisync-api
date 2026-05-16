package com.puccampinas.omnisync.integration.dto;

import java.time.LocalDateTime;

public record MercadoLivreIntegrationStatusResponse(
        boolean connected,
        Long systemClientId,
        Boolean active,
        LocalDateTime expiresAt,
        String marketplace
) {
    public static MercadoLivreIntegrationStatusResponse notConnected(Long systemClientId) {
        return new MercadoLivreIntegrationStatusResponse(false, systemClientId, null, null, null);
    }
}
