package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreSyncResponse;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.Instant;
import com.puccampinas.omnisync.integration.exception.MercadoLivreSyncException;
import com.puccampinas.omnisync.common.exception.ExternalApiException;
import org.springframework.http.HttpStatus;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MercadoLivreCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class MercadoLivreCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MercadoLivreListingService mercadoLivreListingService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void syncSellerListingsShouldReturnSyncSummary() throws Exception {
        MercadoLivreSyncResponse response = new MercadoLivreSyncResponse();
        response.setMessage("Anúncios do Mercado Livre sincronizados com sucesso.");
        response.setSyncedProducts(2);
        response.setCreatedProducts(1);
        response.setUpdatedProducts(1);
        response.setLastSyncAt(Instant.parse("2026-09-08T15:00:00Z"));

        when(productService.syncMercadoLivreProducts("user@test.com", 1L)).thenReturn(response);

        mockMvc.perform(post("/api/integrations/mercadolivre/catalog/1/sync")
                        .principal(new UsernamePasswordAuthenticationToken("user@test.com", null, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Anúncios do Mercado Livre sincronizados com sucesso."))
                .andExpect(jsonPath("$.syncedProducts").value(2))
                .andExpect(jsonPath("$.createdProducts").value(1))
                .andExpect(jsonPath("$.updatedProducts").value(1))
                .andExpect(jsonPath("$.lastSyncAt").value("2026-09-08T15:00:00Z"));
    }

    @Test
    void syncSellerListingsShouldExposeDistinctSafeOutcomes() throws Exception {
        when(productService.syncMercadoLivreProducts("user@test.com", 1L))
                .thenThrow(MercadoLivreSyncException.syncInProgress(Instant.parse("2026-09-08T14:00:00Z")))
                .thenThrow(MercadoLivreSyncException.reauthRequired(null))
                .thenThrow(new ExternalApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "provider body must not leak",
                        "too_many_requests",
                        30
                ));

        var request = post("/api/integrations/mercadolivre/catalog/1/sync")
                .principal(new UsernamePasswordAuthenticationToken("user@test.com", null, List.of()));
        mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("ML_SYNC_IN_PROGRESS"))
                .andExpect(jsonPath("$.lastSyncAt").value("2026-09-08T14:00:00Z"));
        mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ML_REAUTH_REQUIRED"));
        mockMvc.perform(request)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ML_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30))
                .andExpect(jsonPath("$.message").value(
                        "O Mercado Livre limitou temporariamente as sincronizações. Tente novamente mais tarde."
                ));
    }
}
