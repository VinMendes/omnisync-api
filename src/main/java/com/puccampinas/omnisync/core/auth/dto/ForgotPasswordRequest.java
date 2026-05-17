package com.puccampinas.omnisync.core.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(

        @NotBlank(message = "email é obrigatório")
        @Email(message = "email inválido")
        @Size(max = 150, message = "email deve ter no máximo 150 caracteres")
        String email

) {
}