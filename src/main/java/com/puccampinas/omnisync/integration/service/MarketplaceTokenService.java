package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.core.audit.*;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.integration.exception.MercadoLivreSyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MarketplaceTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketplaceTokenService.class);
    private static final long EXPIRATION_SAFETY_WINDOW_SECONDS = 120;

    private final MarketplaceIntegrationRepository repository;
    private final MercadoLivreClient mercadoLivreClient;
    private final TextEncryptor encryptor;
    private final AuditService audit;

    public MarketplaceTokenService(
            MarketplaceIntegrationRepository repository,
            MercadoLivreClient mercadoLivreClient,
            TextEncryptor encryptor,
            AuditService audit
    ) {
        this.repository = repository;
        this.mercadoLivreClient = mercadoLivreClient;
        this.encryptor = encryptor;
        this.audit = audit;
    }

    @Transactional(noRollbackFor = MercadoLivreSyncException.class)
    public String getValidAccessToken(Long systemClientId, Marketplace marketplace) {
        if (marketplace != Marketplace.MERCADO_LIVRE) {
            throw new IllegalArgumentException(
                    "Token lifecycle only implemented for Mercado Livre for now."
            );
        }

        MarketplaceIntegration integration = repository
                .findBySystemClientIdAndMarketplaceForUpdate(systemClientId, marketplace)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active Mercado Livre integration found for systemClientId=" + systemClientId
                ));

        if (!Boolean.TRUE.equals(integration.getActive())) {
            throw MercadoLivreSyncException.reauthRequired(null);
        }

        if (shouldRefresh(integration)) {
            refreshAccessToken(integration);
        }

        return encryptor.decrypt(integration.getAccessToken());
    }

    private boolean shouldRefresh(MarketplaceIntegration integration) {
        return integration.getExpiresAt() == null
                || integration.getExpiresAt().isBefore(
                LocalDateTime.now().plusSeconds(EXPIRATION_SAFETY_WINDOW_SECONDS)
        );
    }

    private void refreshAccessToken(MarketplaceIntegration integration) {
        if (integration.getRefreshToken() == null || integration.getRefreshToken().isBlank()) {
            deactivateAndRequireReauthentication(integration, null);
        }

        LOGGER.info("event=ml_token_refresh_started systemClientId={}", integration.getSystemClientId());
        MercadoLivreTokenResponse refreshedToken;
        try {
            refreshedToken = mercadoLivreClient.refreshAccessToken(
                    encryptor.decrypt(integration.getRefreshToken())
            );
        } catch (ExternalApiException ex) {
            if ("invalid_grant".equals(ex.getProviderCode())) {
                deactivateAndRequireReauthentication(integration, ex);
            }
            LOGGER.warn(
                    "event=ml_token_refresh_failed systemClientId={} providerStatus={}",
                    integration.getSystemClientId(),
                    ex.getStatusCode().value()
            );
            if (ex.isRateLimited()) {
                throw ex;
            }
            throw new ExternalApiException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Mercado Livre token refresh failed."
            );
        }

        if (refreshedToken == null
                || refreshedToken.getAccessToken() == null
                || refreshedToken.getAccessToken().isBlank()
                || refreshedToken.getExpiresIn() == null
                || refreshedToken.getExpiresIn() <= 0) {
            deactivateAndRequireReauthentication(integration, null);
        }

        integration.setAccessToken(encryptor.encrypt(refreshedToken.getAccessToken()));

        if (refreshedToken.getRefreshToken() != null && !refreshedToken.getRefreshToken().isBlank()) {
            integration.setRefreshToken(encryptor.encrypt(refreshedToken.getRefreshToken()));
        }

        integration.setExpiresAt(LocalDateTime.now().plusSeconds(refreshedToken.getExpiresIn()));
        repository.saveAndFlush(integration);
        LOGGER.info("event=ml_token_refresh_succeeded systemClientId={}", integration.getSystemClientId());
    }

    private void deactivateAndRequireReauthentication(
            MarketplaceIntegration integration,
            Throwable cause
    ) {
        var before = AuditSnapshots.integration(integration);
        integration.setActive(false);
        repository.saveAndFlush(integration);
        audit.recordAs(AuditActor.system(integration.getSystemClientId()), AuditAction.DISCONNECT,
                AuditEntityType.INTEGRATION, integration.getId(), before, AuditSnapshots.integration(integration),
                AuditSource.SYSTEM, java.util.Map.of("reason", "REAUTH_REQUIRED"));
        LOGGER.warn("event=ml_reauth_required systemClientId={}", integration.getSystemClientId());
        throw MercadoLivreSyncException.reauthRequired(cause);
    }
}
