package com.puccampinas.omnisync.core.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.service.AuthService;
import com.puccampinas.omnisync.core.users.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void ping() throws Exception {
        mockMvc.perform(get("/api/auth/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void register_success() throws Exception {
        RegisterRequest request = new RegisterRequest(
                1L,
                "Vinicius",
                "vini@email.com",
                "123456",
                null
        );

        User user = new User();
        user.setName("Vinicius");
        user.setEmail("vini@email.com");
        user.setActive(true);

        when(authService.register(any(RegisterRequest.class))).thenReturn(user);
        when(authService.generateAccessToken(user)).thenReturn("access-token");
        when(authService.generateRefreshToken(user)).thenReturn("refresh-token");
        when(authService.buildAuthResponse(
                "Usuário registrado com sucesso",
                user,
                "access-token",
                "refresh-token"
        )).thenReturn(new com.puccampinas.omnisync.core.auth.dto.AuthResponse(
                "Usuário registrado com sucesso",
                null,
                "Vinicius",
                "vini@email.com",
                true,
                "access-token",
                "refresh-token"
        ));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vinicius"))
                .andExpect(jsonPath("$.email").value("vini@email.com"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void login_success() throws Exception {
        LoginRequest request = new LoginRequest(
                "vini@email.com",
                "123456"
        );

        User user = new User();
        user.setName("Vinicius");
        user.setEmail("vini@email.com");
        user.setActive(true);

        when(authService.authenticate(any(LoginRequest.class))).thenReturn(user);
        when(authService.generateAccessToken(user)).thenReturn("access-token");
        when(authService.generateRefreshToken(user)).thenReturn("refresh-token");
        when(authService.buildAuthResponse(
                "Login realizado com sucesso",
                user,
                "access-token",
                "refresh-token"
        )).thenReturn(new com.puccampinas.omnisync.core.auth.dto.AuthResponse(
                "Login realizado com sucesso",
                null,
                "Vinicius",
                "vini@email.com",
                true,
                "access-token",
                "refresh-token"
        ));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vinicius"))
                .andExpect(jsonPath("$.email").value("vini@email.com"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void refresh_withBearerToken() throws Exception {
        when(authService.refreshAccessToken("refresh-token"))
                .thenReturn("new-access-token");

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer refresh-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"));
    }

    @Test
    void refresh_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_success() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(content().string("Logout realizado com sucesso"));
    }
}