package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.exception.MercadoLivreSyncException;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceTokenServiceTest {

    private MarketplaceIntegrationRepository repository;
    private MercadoLivreClient client;
    private TextEncryptor encryptor;
    private MarketplaceTokenService service;
    private MarketplaceIntegration integration;

    @BeforeEach
    void setUp() {
        repository = mock(MarketplaceIntegrationRepository.class);
        client = mock(MercadoLivreClient.class);
        encryptor = mock(TextEncryptor.class);
        service = new MarketplaceTokenService(repository, client, encryptor, mock(com.puccampinas.omnisync.core.audit.AuditService.class));
        integration = new MarketplaceIntegration();
        integration.setId(10L);
        integration.setSystemClientId(7L);
        integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setActive(true);
        integration.setAccessToken("encrypted-old-access");
        integration.setRefreshToken("encrypted-refresh");
        integration.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findBySystemClientIdAndMarketplaceForUpdate(7L, Marketplace.MERCADO_LIVRE))
                .thenReturn(Optional.of(integration));
        when(encryptor.decrypt("encrypted-refresh")).thenReturn("refresh-token");
    }

    @Test
    void rotatesAndPersistsCredentialsBeforeReturningTheAccessToken() {
        MercadoLivreTokenResponse token = new MercadoLivreTokenResponse();
        token.setAccessToken("new-access");
        token.setRefreshToken("new-refresh");
        token.setExpiresIn(3600L);
        when(client.refreshAccessToken("refresh-token")).thenReturn(token);
        when(encryptor.encrypt("new-access")).thenReturn("encrypted-new-access");
        when(encryptor.encrypt("new-refresh")).thenReturn("encrypted-new-refresh");
        when(encryptor.decrypt("encrypted-new-access")).thenReturn("new-access");

        assertThat(service.getValidAccessToken(7L, Marketplace.MERCADO_LIVRE)).isEqualTo("new-access");
        assertThat(integration.getRefreshToken()).isEqualTo("encrypted-new-refresh");
        assertThat(integration.getExpiresAt()).isAfter(LocalDateTime.now());
        verify(repository).saveAndFlush(integration);
    }

    @Test
    void invalidGrantDeactivatesOnlyTheIntegrationAndRequiresReauthentication() {
        when(client.refreshAccessToken("refresh-token")).thenThrow(
                new ExternalApiException(HttpStatus.BAD_REQUEST, "safe", "invalid_grant", null)
        );

        assertThatThrownBy(() -> service.getValidAccessToken(7L, Marketplace.MERCADO_LIVRE))
                .isInstanceOf(MercadoLivreSyncException.class)
                .extracting("code")
                .isEqualTo("ML_REAUTH_REQUIRED");
        assertThat(integration.getActive()).isFalse();
        verify(repository).saveAndFlush(integration);
    }

    @Test
    void rateLimitDoesNotDeactivateTheIntegration() {
        ExternalApiException rateLimit = new ExternalApiException(
                HttpStatus.TOO_MANY_REQUESTS, "safe", "too_many_requests", 30
        );
        when(client.refreshAccessToken("refresh-token")).thenThrow(rateLimit);

        assertThatThrownBy(() -> service.getValidAccessToken(7L, Marketplace.MERCADO_LIVRE))
                .isSameAs(rateLimit);
        assertThat(integration.getActive()).isTrue();
    }
}
