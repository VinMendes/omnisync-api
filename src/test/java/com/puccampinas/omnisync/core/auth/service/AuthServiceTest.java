package com.puccampinas.omnisync.core.auth.service;

import com.puccampinas.omnisync.core.auth.dto.AuthResponse;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetEmailService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetTokenRepository;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TenantRoleRepository tenantRoleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private Claims claims;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private CustomUserDetailsService userDetailsService;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private PasswordResetEmailService passwordResetEmailService;

    private AuthService authService;

    @BeforeEach
    void setup() {
        authService = new AuthService(
                userRepository, tenantRoleRepository, passwordEncoder, jwtService, passwordResetTokenRepository,
                passwordResetEmailService, "https://frontend.example/reset-password",
                authenticationManager, userDetailsService, mock(com.puccampinas.omnisync.core.audit.AuditService.class)
        );
    }

    @Test
    void register_success() {
        RegisterRequest request = new RegisterRequest(
                1L, "Vinicius", "  VINI@EMAIL.COM  ", "123456", Map.of("cpf", "test")
        );
        when(userRepository.existsByEmail("vini@email.com")).thenReturn(false);
        when(tenantRoleRepository.save(any(TenantRole.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode("123456")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User savedUser = authService.register(request);

        assertThat(savedUser.getName()).isEqualTo("Vinicius");
        assertThat(savedUser.getEmail()).isEqualTo("vini@email.com");
        assertThat(savedUser.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(savedUser.getActive()).isTrue();
        assertThat(savedUser.getResource().attributes()).containsEntry("cpf", "test");
        assertThat(savedUser.getTenantRole().getName()).isEqualTo(Role.VIEWER);
        assertThat(savedUser.getTenantRole().getSystemClientId()).isEqualTo(1L);
        assertThat(savedUser.getTenantRole().getPermissions()).isEqualTo(Role.VIEWER.defaultPermissions());
        verify(tenantRoleRepository).save(savedUser.getTenantRole());
        verify(userRepository).save(any(User.class));
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void register_shouldThrowWhenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest(1L, "Vinicius", "vini@email.com", "123456", null);
        when(userRepository.existsByEmail("vini@email.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Já existe usuário com esse email");
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void login_shouldDelegateCredentialsToManagerAndPreserveResponseContract() {
        OmniUserPrincipal principal = principal();
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        when(jwtService.generateAccessToken("user@example.com")).thenReturn("access-token");
        when(jwtService.generateRefreshToken("user@example.com")).thenReturn("refresh-token");

        AuthResponse response = authService.login(new LoginRequest(" USER@Example.COM ", "valid-password"));

        ArgumentCaptor<Authentication> submitted = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(submitted.capture());
        assertThat(submitted.getValue().isAuthenticated()).isFalse();
        assertThat(submitted.getValue().getName()).isEqualTo("user@example.com");
        assertThat(submitted.getValue().getCredentials()).isEqualTo("valid-password");
        assertThat(response).isEqualTo(new AuthResponse(
                "Login realizado com sucesso", 1L, "Usuário de teste", "user@example.com",
                true, "access-token", "refresh-token"
        ));
        verifyNoInteractions(userRepository, passwordEncoder, userDetailsService);
    }

    @Test
    void login_shouldNotIssueTokensAfterAuthenticationFailure() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(jwtService, userRepository, passwordEncoder);
    }

    @Test
    void generateAccessToken_shouldKeepEmailSubjectForExistingClients() {
        User user = new User();
        user.setEmail("user@example.com");
        when(jwtService.generateAccessToken("user@example.com")).thenReturn("token");

        assertThat(authService.generateAccessToken(user)).isEqualTo("token");
    }

    @Test
    void generateRefreshToken_shouldKeepEmailSubjectForExistingClients() {
        User user = new User();
        user.setEmail("user@example.com");
        when(jwtService.generateRefreshToken("user@example.com")).thenReturn("refresh");

        assertThat(authService.generateRefreshToken(user)).isEqualTo("refresh");
    }

    @Test
    void refreshAccessToken_shouldLoadCurrentActivePrincipal() {
        stubRefreshClaims();
        when(userDetailsService.loadActiveUserByUsername("user@example.com")).thenReturn(principal());
        when(jwtService.generateAccessToken("user@example.com")).thenReturn("new-access-token");

        assertThat(authService.refreshAccessToken("refresh-token")).isEqualTo("new-access-token");
        verify(jwtService).validateAndGetClaims("refresh-token", JwtService.TYPE_REFRESH);
        verify(userDetailsService).loadActiveUserByUsername("user@example.com");
        verifyNoInteractions(userRepository, passwordEncoder, authenticationManager);
    }

    @Test
    void refreshAccessToken_shouldRejectMissingUser() {
        stubRefreshClaims();
        when(userDetailsService.loadActiveUserByUsername("user@example.com"))
                .thenThrow(new UsernameNotFoundException("Usuário não encontrado."));

        assertThatThrownBy(() -> authService.refreshAccessToken("refresh-token"))
                .isInstanceOf(UsernameNotFoundException.class);
        verify(jwtService, never()).generateAccessToken(any());
    }

    @Test
    void refreshAccessToken_shouldRejectInactiveAccount() {
        stubRefreshClaims();
        when(userDetailsService.loadActiveUserByUsername("user@example.com"))
                .thenThrow(new DisabledException("User is disabled"));

        assertThatThrownBy(() -> authService.refreshAccessToken("refresh-token"))
                .isInstanceOf(DisabledException.class);
        verify(jwtService, never()).generateAccessToken(any());
    }

    @Test
    void refreshAccessToken_shouldValidateBeforeQueryingUser() {
        when(jwtService.validateAndGetClaims("invalid-token", JwtService.TYPE_REFRESH))
                .thenThrow(new JwtException("Invalid token"));

        assertThatThrownBy(() -> authService.refreshAccessToken("invalid-token"))
                .isInstanceOf(JwtException.class);
        verifyNoInteractions(userDetailsService, userRepository);
    }

    @Test
    void buildAuthResponse_shouldPreserveRegistrationContract() {
        User user = new User();
        user.setName("Vinicius");
        user.setEmail("vini@email.com");
        user.setActive(true);

        AuthResponse response = authService.buildAuthResponse(
                "Usuário registrado com sucesso", user, "access-token", "refresh-token"
        );

        assertThat(response.name()).isEqualTo("Vinicius");
        assertThat(response.email()).isEqualTo("vini@email.com");
        assertThat(response.active()).isTrue();
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    private void stubRefreshClaims() {
        when(jwtService.validateAndGetClaims("refresh-token", JwtService.TYPE_REFRESH)).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user@example.com");
    }

    private OmniUserPrincipal principal() {
        return new OmniUserPrincipal(
                1L, 10L, "Usuário de teste", "user@example.com", null, true, true, List.of()
        );
    }

}
