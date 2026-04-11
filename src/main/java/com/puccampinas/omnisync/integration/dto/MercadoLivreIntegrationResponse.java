package com.puccampinas.omnisync.integration.dto;

import com.puccampinas.omnisync.common.enums.Marketplace;

import java.time.LocalDateTime;
import java.util.Map;

public record MercadoLivreIntegrationResponse(
        String message,
        Long systemClientId,
        Marketplace marketplace,
        Boolean active,
        LocalDateTime expiresAt,
        Map<String, Object> resource
) {
}
