package com.puccampinas.omnisync.integration.dto;

import jakarta.validation.constraints.NotBlank;

public record MercadoLivreCodeExchangeRequest(
        @NotBlank(message = "code is required")
        String code,

        @NotBlank(message = "state is required")
        String state
) {
}
