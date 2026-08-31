package com.puccampinas.omnisync.core.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OmniUserPrincipalTest {

    @Test
    void shouldExposeIdentityWithoutExposingCredentialsInJson() throws Exception {
        OmniUserPrincipal principal = principal(true, true);

        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getSystemClientId()).isEqualTo(10L);
        assertThat(principal.getName()).isEqualTo("Usuário de teste");
        assertThat(principal.getUsername()).isEqualTo("user@example.com");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(principal))
                .doesNotContain("password", "secret-hash");

        principal.eraseCredentials();

        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getUsername()).isEqualTo("user@example.com");
    }

    @Test
    void shouldRequireBothUserAndCompanyToBeActive() {
        assertThat(principal(false, true).isEnabled()).isFalse();
        assertThat(principal(true, false).isEnabled()).isFalse();
        assertThat(principal(false, false).isEnabled()).isFalse();
    }

    @Test
    void shouldSnapshotAuthoritiesInsteadOfSharingMutableInput() {
        var authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("test:read")));
        OmniUserPrincipal principal = new OmniUserPrincipal(
                1L, 10L, "Usuário de teste", "user@example.com", null, true, true, authorities
        );
        authorities.clear();

        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("test:read");
    }

    private OmniUserPrincipal principal(boolean userActive, boolean clientActive) {
        return new OmniUserPrincipal(
                1L, 10L, "Usuário de teste", "user@example.com", "secret-hash", userActive, clientActive, List.of()
        );
    }
}
