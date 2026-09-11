package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.config.security.TenantAccess;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreIntegrationResponse;
import com.puccampinas.omnisync.integration.service.MercadoLivreAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MercadoLivreAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class MercadoLivreAuthControllerTest {

    @MockitoBean
    private TenantAccess tenantAccess;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private MercadoLivreAuthService service;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void exchangeConfirmsIntegrationWithoutAnyMarketplaceCredential() throws Exception {
        when(service.exchangeCodeForAuthenticatedUser("user@test.com", "state", "code"))
                .thenReturn(new MercadoLivreIntegrationResponse(
                        "connected",
                        7L,
                        Marketplace.MERCADO_LIVRE,
                        true,
                        LocalDateTime.parse("2026-09-08T15:00:00"),
                        Map.of("user_id", 123L)
                ));

        mockMvc.perform(post("/api/integrations/mercadolivre/exchange")
                        .contentType("application/json")
                        .content("{\"code\":\"code\",\"state\":\"state\"}")
                        .principal(new UsernamePasswordAuthenticationToken("user@test.com", null, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }
}
