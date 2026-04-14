package com.puccampinas.omnisync.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MercadoLivreNotificationRequest(
        String resource,
        @JsonProperty("user_id") Long userId,
        String topic,
        @JsonProperty("application_id") Long applicationId,
        Integer attempts,
        String sent,
        String received
) {
}
