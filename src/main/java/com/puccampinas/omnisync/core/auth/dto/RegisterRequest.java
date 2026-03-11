package com.puccampinas.omnisync.core.auth.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotNull(message = "systemClientId é obrigatório")
        Long systemClientId,

        @NotBlank(message = "name é obrigatório")
        @Size(max = 150, message = "name deve ter no máximo 150 caracteres")
        String name,

        @NotBlank(message = "email é obrigatório")
        @Email(message = "email inválido")
        @Size(max = 150, message = "email deve ter no máximo 150 caracteres")
        String email,

        @NotBlank(message = "password é obrigatório")
        @Size(min = 6, max = 100, message = "password deve ter entre 6 e 100 caracteres")
        String password,

        JsonNode resource
) {
}