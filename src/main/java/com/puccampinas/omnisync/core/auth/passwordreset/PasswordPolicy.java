package com.puccampinas.omnisync.core.auth.passwordreset;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Existing 6–100 character policy, with the BCrypt UTF-8 byte limit enforced explicitly. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 100)
            throw new IllegalArgumentException("A nova senha deve ter entre 6 e 100 caracteres e não pode ser vazia.");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("A nova senha deve ter no máximo 72 bytes em UTF-8.");
    }

    public static void validateConfirmation(String password, String confirmation) {
        validate(password);
        if (!Objects.equals(password, confirmation))
            throw new IllegalArgumentException("A confirmação deve ser igual à nova senha.");
    }
}
