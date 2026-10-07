package com.puccampinas.omnisync.core.users;

import com.puccampinas.omnisync.core.auth.passwordreset.PasswordPolicy;
import com.puccampinas.omnisync.core.auth.dto.ResetPasswordRequest;
import com.puccampinas.omnisync.core.users.dto.AdminPasswordResetRequest;
import com.puccampinas.omnisync.core.users.dto.ChangePasswordRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    @Test void acceptsMinimumAndBcryptByteBoundary() {
        for (String password : new String[]{"123456", "a".repeat(72), "á".repeat(36)}) {
            assertThatCode(() -> PasswordPolicy.validateConfirmation(password, password)).doesNotThrowAnyException();
        }
    }

    @Test void rejectsMissingShortAndOverByteLimitPasswords() {
        for (String password : new String[]{null, "", "      ", "12345", "a".repeat(73), "á".repeat(37)}) {
            assertThatThrownBy(() -> PasswordPolicy.validate(password)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void comparesConfirmationExactly() {
        assertThatThrownBy(() -> PasswordPolicy.validateConfirmation("Secret123", "Secret123 "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void credentialDtosDoNotExposeValuesInToString() {
        assertThat(new ChangePasswordRequest("OLD-SECRET", "NEW-SECRET", "NEW-SECRET").toString())
                .doesNotContain("OLD-SECRET", "NEW-SECRET");
        assertThat(new AdminPasswordResetRequest("NEW-SECRET", "NEW-SECRET").toString()).doesNotContain("NEW-SECRET");
        assertThat(new ResetPasswordRequest("RESET-TOKEN", "NEW-SECRET").toString()).doesNotContain("RESET-TOKEN", "NEW-SECRET");
    }
}
