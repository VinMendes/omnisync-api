package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MarketplaceTokenService {

    private static final long EXPIRATION_SAFETY_WINDOW_SECONDS = 120;

    private final MarketplaceIntegrationRepository repository;
    private final MercadoLivreClient mercadoLivreClient;
    private final TextEncryptor encryptor;

    public MarketplaceTokenService(
            MarketplaceIntegrationRepository repository,
            MercadoLivreClient mercadoLivreClient,
            TextEncryptor encryptor
    ) {
        this.repository = repository;
        this.mercadoLivreClient = mercadoLivreClient;
        this.encryptor = encryptor;
    }

    @Transactional
    public String getValidAccessToken(Long systemClientId, Marketplace marketplace) {
        if (marketplace != Marketplace.MERCADO_LIVRE) {
            throw new IllegalArgumentException(
                    "Token lifecycle only implemented for Mercado Livre for now."
            );
        }

        MarketplaceIntegration integration = repository
                .findBySystemClientIdAndMarketplaceAndActiveTrue(
                        systemClientId,
                        marketplace
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active Mercado Livre integration found for systemClientId=" + systemClientId
                ));

        if (shouldRefresh(integration)) {
            refreshAccessToken(integration);
        }

        return encryptor.decrypt(integration.getAccessToken());
    }

    private boolean shouldRefresh(MarketplaceIntegration integration) {
        return integration.getExpiresAt() == null ||
                integration.getExpiresAt().isBefore(
                        LocalDateTime.now().plusSeconds(EXPIRATION_SAFETY_WINDOW_SECONDS)
                );
    }

    private void refreshAccessToken(MarketplaceIntegration integration) {
        if (integration.getRefreshToken() == null) {
            throw new IllegalStateException(
                    "Refresh token is not available for marketplace integration id=" + integration.getId()
            );
        }

        MercadoLivreTokenResponse refreshedToken = mercadoLivreClient.refreshAccessToken(
                encryptor.decrypt(integration.getRefreshToken())
        );

        if (refreshedToken == null || refreshedToken.getAccessToken() == null || refreshedToken.getExpiresIn() == null) {
            throw new IllegalStateException("Mercado Livre refresh token response is invalid.");
        }

        integration.setAccessToken(encryptor.encrypt(refreshedToken.getAccessToken()));

        if (refreshedToken.getRefreshToken() != null) {
            integration.setRefreshToken(
                    encryptor.encrypt(refreshedToken.getRefreshToken())
            );
        }

        integration.setExpiresAt(LocalDateTime.now().plusSeconds(refreshedToken.getExpiresIn()));
        repository.save(integration);
    }
}
