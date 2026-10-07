package com.puccampinas.omnisync.core.users.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @JsonProperty("current_password") @NotBlank(message = "A senha atual é obrigatória.") String currentPassword,
        @JsonProperty("new_password") @NotBlank(message = "A nova senha é obrigatória.")
        @Size(min = 6, max = 100, message = "A nova senha deve ter entre 6 e 100 caracteres.") String newPassword,
        @JsonProperty("new_password_confirmation") @NotBlank(message = "A confirmação é obrigatória.") String confirmation
) {
    @Override public String toString() { return "ChangePasswordRequest[REDACTED]"; }
}
