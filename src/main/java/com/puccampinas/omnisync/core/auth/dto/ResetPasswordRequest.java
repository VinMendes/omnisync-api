package com.puccampinas.omnisync.core.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @NotBlank(message = "token é obrigatório")
        String token,

        @NotBlank(message = "newPassword é obrigatório")
        @Size(min = 6, max = 100, message = "newPassword deve ter entre 6 e 100 caracteres")
        String newPassword

) {
}