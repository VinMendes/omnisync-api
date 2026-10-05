package com.puccampinas.omnisync.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record MercadoLivreNotificationRequest(
        @JsonProperty("_id") @Size(max = 120) String providerEventId,
        @NotBlank
        @Size(max = 500)
        String resource,
        @NotNull
        @Positive
        @JsonProperty("user_id") Long userId,
        @NotBlank
        @Size(max = 80)
        String topic,
        @NotNull
        @Positive
        @JsonProperty("application_id") Long applicationId,
        @PositiveOrZero
        Integer attempts,
        String sent,
        String received
) {
    public MercadoLivreNotificationRequest(
            String resource,
            Long userId,
            String topic,
            Long applicationId,
            Integer attempts,
            String sent,
            String received
    ) {
        this(null, resource, userId, topic, applicationId, attempts, sent, received);
    }
}
