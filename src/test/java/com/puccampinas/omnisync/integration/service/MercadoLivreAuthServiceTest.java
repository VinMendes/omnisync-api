package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MercadoLivreAuthServiceTest {

    private final MarketplaceIntegrationRepository repository = mock(MarketplaceIntegrationRepository.class);
    private final MercadoLivreAuthService service = new MercadoLivreAuthService(
            mock(MercadoLivreClient.class),
            repository,
            mock(SystemClientService.class),
            mock(UserService.class),
            mock(TextEncryptor.class),
            mock(com.puccampinas.omnisync.core.audit.AuditService.class),
            mock(com.puccampinas.omnisync.core.users.repository.UserRepository.class)
    );

    @Test
    void statusUsesNullBeforeFirstSuccessfulSync() {
        MarketplaceIntegration integration = integration(false, null);
        when(repository.findBySystemClientIdAndMarketplace(7L, Marketplace.MERCADO_LIVRE))
                .thenReturn(Optional.of(integration));

        var status = service.getStatus(7L);

        assertThat(status.connected()).isTrue();
        assertThat(status.active()).isFalse();
        assertThat(status.lastSyncAt()).isNull();
    }

    @Test
    void statusPreservesTheCanonicalPersistedInstant() {
        Instant lastSyncAt = Instant.parse("2026-09-08T15:00:00Z");
        when(repository.findBySystemClientIdAndMarketplace(7L, Marketplace.MERCADO_LIVRE))
                .thenReturn(Optional.of(integration(true, lastSyncAt)));

        assertThat(service.getStatus(7L).lastSyncAt()).isEqualTo(lastSyncAt);
    }

    private MarketplaceIntegration integration(boolean active, Instant lastSyncAt) {
        MarketplaceIntegration integration = new MarketplaceIntegration();
        integration.setSystemClientId(7L);
        integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setActive(active);
        integration.setLastSyncAt(lastSyncAt);
        return integration;
    }
}
