package com.puccampinas.omnisync.core.auth.service;

import com.puccampinas.omnisync.core.auth.dto.AuthResponse;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private Claims claims;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void register_success() {
        RegisterRequest request = new RegisterRequest(
                1L,
                "Vinicius",
                "  VINI@EMAIL.COM  ",
                "123456",
                null
        );

        when(userRepository.existsByEmail("vini@email.com")).thenReturn(false);
        when(passwordEncoder.encode("123456")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User savedUser = authService.register(request);

        assertNotNull(savedUser);
        assertEquals("Vinicius", savedUser.getName());
        assertEquals("vini@email.com", savedUser.getEmail());
        assertEquals("hashed-password", savedUser.getPasswordHash());
        assertTrue(savedUser.getActive());

        verify(userRepository).existsByEmail("vini@email.com");
        verify(passwordEncoder).encode("123456");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_shouldThrowWhenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest(
                1L,
                "Vinicius",
                "vini@email.com",
                "123456",
                null
        );

        when(userRepository.existsByEmail("vini@email.com")).thenReturn(true);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> authService.register(request));

        assertEquals("Já existe usuário com esse email", exception.getMessage());

        verify(userRepository).existsByEmail("vini@email.com");
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void authenticate_success() {
        LoginRequest request = new LoginRequest(
                "teste@email.com",
                "123456"
        );

        User user = new User();
        user.setEmail("teste@email.com");
        user.setPasswordHash("hash");
        user.setActive(true);

        when(userRepository.findByEmail("teste@email.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("123456", "hash"))
                .thenReturn(true);

        User result = authService.authenticate(request);

        assertNotNull(result);
        assertEquals("teste@email.com", result.getEmail());

        verify(userRepository).findByEmail("teste@email.com");
        verify(passwordEncoder).matches("123456", "hash");
    }

    @Test
    void authenticate_shouldThrowWhenUserNotFound() {
        LoginRequest request = new LoginRequest(
                "naoexiste@email.com",
                "123456"
        );

        when(userRepository.findByEmail("naoexiste@email.com"))
                .thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> authService.authenticate(request));

        assertEquals("Credenciais inválidas", exception.getMessage());

        verify(userRepository).findByEmail("naoexiste@email.com");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void authenticate_shouldThrowWhenUserIsInactive() {
        LoginRequest request = new LoginRequest(
                "teste@email.com",
                "123456"
        );

        User user = new User();
        user.setEmail("teste@email.com");
        user.setPasswordHash("hash");
        user.setActive(false);

        when(userRepository.findByEmail("teste@email.com"))
                .thenReturn(Optional.of(user));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> authService.authenticate(request));

        assertEquals("Usuário inativo", exception.getMessage());

        verify(userRepository).findByEmail("teste@email.com");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void authenticate_shouldThrowWhenPasswordIsInvalid() {
        LoginRequest request = new LoginRequest(
                "teste@email.com",
                "senha-errada"
        );

        User user = new User();
        user.setEmail("teste@email.com");
        user.setPasswordHash("hash");
        user.setActive(true);

        when(userRepository.findByEmail("teste@email.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("senha-errada", "hash"))
                .thenReturn(false);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> authService.authenticate(request));

        assertEquals("Credenciais inválidas", exception.getMessage());

        verify(userRepository).findByEmail("teste@email.com");
        verify(passwordEncoder).matches("senha-errada", "hash");
    }

    @Test
    void generateAccessToken() {
        User user = new User();
        user.setEmail("teste@email.com");

        when(jwtService.generateAccessToken("teste@email.com"))
                .thenReturn("token");

        String token = authService.generateAccessToken(user);

        assertEquals("token", token);

        verify(jwtService).generateAccessToken("teste@email.com");
    }

    @Test
    void generateRefreshToken() {
        User user = new User();
        user.setEmail("teste@email.com");

        when(jwtService.generateRefreshToken("teste@email.com"))
                .thenReturn("refresh");

        String token = authService.generateRefreshToken(user);

        assertEquals("refresh", token);

        verify(jwtService).generateRefreshToken("teste@email.com");
    }

    @Test
    void refreshAccessToken_success() {
        String refreshToken = "valid-refresh-token";

        when(jwtService.validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH))
                .thenReturn(claims);
        when(claims.getSubject()).thenReturn("teste@email.com");

        User user = new User();
        user.setEmail("teste@email.com");
        user.setActive(true);

        when(userRepository.findByEmail("teste@email.com"))
                .thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken("teste@email.com"))
                .thenReturn("new-access-token");

        String result = authService.refreshAccessToken(refreshToken);

        assertEquals("new-access-token", result);

        verify(jwtService).validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH);
        verify(userRepository).findByEmail("teste@email.com");
        verify(jwtService).generateAccessToken("teste@email.com");
    }

    @Test
    void refreshAccessToken_shouldThrowWhenUserNotFound() {
        String refreshToken = "valid-refresh-token";

        when(jwtService.validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH))
                .thenReturn(claims);
        when(claims.getSubject()).thenReturn("naoexiste@email.com");

        when(userRepository.findByEmail("naoexiste@email.com"))
                .thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> authService.refreshAccessToken(refreshToken));

        assertEquals("Usuário do refresh não encontrado", exception.getMessage());

        verify(jwtService).validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH);
        verify(userRepository).findByEmail("naoexiste@email.com");
        verify(jwtService, never()).generateAccessToken(any());
    }

    @Test
    void refreshAccessToken_shouldThrowWhenUserIsInactive() {
        String refreshToken = "valid-refresh-token";

        when(jwtService.validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH))
                .thenReturn(claims);
        when(claims.getSubject()).thenReturn("teste@email.com");

        User user = new User();
        user.setEmail("teste@email.com");
        user.setActive(false);

        when(userRepository.findByEmail("teste@email.com"))
                .thenReturn(Optional.of(user));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> authService.refreshAccessToken(refreshToken));

        assertEquals("Usuário inativo", exception.getMessage());

        verify(jwtService).validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH);
        verify(userRepository).findByEmail("teste@email.com");
        verify(jwtService, never()).generateAccessToken(any());
    }

    @Test
    void buildAuthResponse() {
        User user = new User();
        user.setName("Vinicius");
        user.setEmail("vini@email.com");
        user.setActive(true);

        AuthResponse response = authService.buildAuthResponse(
                "Login realizado com sucesso",
                user,
                "access-token",
                "refresh-token"
        );

        assertNotNull(response);
        assertEquals("Login realizado com sucesso", response.message());
        assertEquals("Vinicius", response.name());
        assertEquals("vini@email.com", response.email());
        assertTrue(response.active());
        assertEquals("access-token", response.accessToken());
        assertEquals("refresh-token", response.refreshToken());
    }
}