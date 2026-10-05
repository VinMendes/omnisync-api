package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreNotificationRequest;
import com.puccampinas.omnisync.integration.service.MarketplaceWebhookInboxService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MercadoLivreWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
class MercadoLivreWebhookControllerTest {

    private static final String VALID_NOTIFICATION = """
            {
              "resource": "/orders/2001",
              "user_id": 123456789,
              "topic": "orders_v2",
              "application_id": 999,
              "attempts": 1,
              "sent": "2026-09-10T15:20:30Z",
              "received": "2026-09-10T15:20:31Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketplaceWebhookInboxService inboxService;

    @MockitoBean
    private TenantAccess tenantAccess;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void canonicalPathShouldAcknowledgeFirstReceipt() throws Exception {
        when(inboxService.receive(any(MercadoLivreNotificationRequest.class)))
                .thenReturn(Map.of(
                        "accepted", true,
                        "duplicate", false,
                        "correlation_id", 501L
                ));

        mockMvc.perform(post("/api/integrations/mercadolivre/webhooks/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NOTIFICATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.correlation_id").value(501));
    }

    @Test
    void legacyPathShouldAcknowledgeDuplicateReceiptWithSameCorrelation() throws Exception {
        when(inboxService.receive(any(MercadoLivreNotificationRequest.class)))
                .thenReturn(Map.of(
                        "accepted", true,
                        "duplicate", true,
                        "correlation_id", 501L
                ));

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NOTIFICATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.correlation_id").value(501));
    }

    @Test
    void malformedPayloadShouldBeRejectedBeforeReceiptIsPersisted() throws Exception {
        mockMvc.perform(post("/api/integrations/mercadolivre/webhooks/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(inboxService, never()).receive(any(MercadoLivreNotificationRequest.class));
    }

    @Test
    void unknownIntegrationShouldReturnNotFoundWithoutAcknowledgingReceipt() throws Exception {
        when(inboxService.receive(any(MercadoLivreNotificationRequest.class)))
                .thenThrow(new EntityNotFoundException(
                        "Active Mercado Livre integration not found for user_id=123456789"
                ));

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NOTIFICATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("Active Mercado Livre integration not found for user_id=123456789"));
    }
}
