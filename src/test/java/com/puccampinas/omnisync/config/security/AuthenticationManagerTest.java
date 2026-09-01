package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationManagerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SystemClientRepository systemClientRepository;

    private AuthenticationManager manager;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        SecurityBeansConfig config = new SecurityBeansConfig();
        passwordEncoder = config.passwordEncoder();
        var userDetailsService = new CustomUserDetailsService(userRepository, systemClientRepository);
        manager = config.authenticationManager(config.authenticationProvider(userDetailsService, passwordEncoder));
    }

    @Test
    void shouldAuthenticateWithRealDaoProviderAndBcryptAndEraseCredentials() {
        stubAccount(true, true);

        Authentication authentication = authenticate(" USER@Example.COM ", "valid-password");

        assertThat(manager).isInstanceOf(ProviderManager.class);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("user@example.com");
        assertThat(authentication.getCredentials()).isNull();
        OmniUserPrincipal principal = (OmniUserPrincipal) authentication.getPrincipal();
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getSystemClientId()).isEqualTo(10L);
        assertThat(principal.getPassword()).isNull();
    }

    @Test
    void shouldRejectWrongPassword() {
        stubAccount(true, true);

        assertThatThrownBy(() -> authenticate("user@example.com", "wrong-password"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void shouldHideWhetherTheUserExists() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticate("missing@example.com", "valid-password"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void shouldRejectInactiveUser() {
        stubAccount(false, true);

        assertThatThrownBy(() -> authenticate("user@example.com", "valid-password"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void shouldRejectInactiveCompany() {
        stubAccount(true, false);

        assertThatThrownBy(() -> authenticate("user@example.com", "valid-password"))
                .isInstanceOf(DisabledException.class);
    }

    private Authentication authenticate(String email, String password) {
        return manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
    }

    private void stubAccount(boolean userActive, boolean clientActive) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setSystemClientId(10L);
        user.setName("Usuário de teste");
        user.setEmail("user@example.com");
        user.setPasswordHash(passwordEncoder.encode("valid-password"));
        user.setActive(userActive);
        TenantRole role = new TenantRole();
        role.setSystemClientId(10L);
        role.setName(Role.VIEWER);
        role.setResource(RoleResource.of(Role.VIEWER.defaultPermissions()));
        user.setTenantRole(role);
        SystemClient client = new SystemClient();
        client.setId(10L);
        client.setActive(clientActive);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(systemClientRepository.findById(10L)).thenReturn(Optional.of(client));
    }
}
